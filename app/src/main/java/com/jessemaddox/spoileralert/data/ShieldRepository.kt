package com.jessemaddox.spoileralert.data

import android.app.NotificationManager
import android.content.Context
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jessemaddox.spoileralert.domain.Alias
import com.jessemaddox.spoileralert.domain.ArmedShield
import com.jessemaddox.spoileralert.domain.GameAliases
import com.jessemaddox.spoileralert.domain.EventIdentity
import com.jessemaddox.spoileralert.domain.KeywordExpansion
import com.jessemaddox.spoileralert.domain.Matcher
import com.jessemaddox.spoileralert.domain.PlayerCatalog
import com.jessemaddox.spoileralert.domain.SourcePolicy
import com.jessemaddox.spoileralert.domain.Team
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.domain.SportsSpoilerTerms
import com.jessemaddox.spoileralert.schedule.PregamePromptWorker
import com.jessemaddox.spoileralert.service.AutoDisarmWorker
import com.jessemaddox.spoileralert.service.CheckIn
import com.jessemaddox.spoileralert.service.CheckInWorker
import com.jessemaddox.spoileralert.service.CatchUpNotifier
import com.jessemaddox.spoileralert.service.EventCompletionWorker
import com.jessemaddox.spoileralert.service.MaskedNotifier
import com.jessemaddox.spoileralert.service.SummaryNotifier
import com.jessemaddox.spoileralert.service.ActiveNotificationSweep
import com.jessemaddox.spoileralert.ui.AppPrefs
import com.jessemaddox.spoileralert.ui.SessionDurations
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock
import com.jessemaddox.spoileralert.service.SessionEffects
import com.jessemaddox.spoileralert.service.SessionActions

object ShieldCodec {
    /** Unanswered check-ins keep interception active for one final hour before sealing. */
    val GRACE_MS = TimeUnit.HOURS.toMillis(1)

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Alias.serializer())
    fun encodeAliases(aliases: List<Alias>): String = json.encodeToString(listSerializer, aliases)
    fun decodeAliases(text: String): List<Alias> = json.decodeFromString(listSerializer, text)
    fun toArmedShield(e: ShieldEntity): ArmedShield {
        val stored = decodeAliases(e.aliasesJson)
        // Custom keywords are user-typed; expand simple inflections at decode time so
        // "test" also matches "tests" — including for shields created before this existed.
        val aliases = when (e.kind) {
            "CUSTOM" -> stored.flatMap { KeywordExpansion.expand(it) }.distinct()
            "TEAM", "GAME" -> (stored + SportsSpoilerTerms.aliases).distinctBy { it.text }
            else -> stored
        }
        return ArmedShield(
            id = e.id, name = e.name, aliases = aliases, maskedAlerts = e.maskedAlerts,
            matchesPatterns = e.kind == "TEAM" || e.kind == "GAME",
        )
    }

    /** User-selected session deadline, when the check-in decision is due. */
    fun sessionDeadlineMillis(e: ShieldEntity): Long? =
        e.armedAtMillis?.plus(TimeUnit.HOURS.toMillis(e.autoDisarmHours.toLong()))

    /** Absolute protection deadline, including the fixed unanswered-check-in grace window. */
    fun expiresAtMillis(e: ShieldEntity): Long? = sessionDeadlineMillis(e)?.plus(GRACE_MS)
}

class ShieldRepository(
    private val context: Context,
    /** Defaults to the real app singleton; instrumented tests can pass an isolated in-memory
     *  [AppDatabase] instead so repository-level behavior (create/refresh/reuse/cleanup) is
     *  testable without touching the on-device database. */
    private val db: AppDatabase = AppDatabase.get(context),
) {
    val sessions = ProtectionSessions(db)
    fun sessionsChanged() = db.protectionSessionDao().observeAll()
    fun policy() = ProtectionPolicy(AppPrefs.excludedPackages(context), AppPrefs.fantasyPackages(context), context.packageName)

    val shieldDao get() = db.shieldDao()
    val vaultDao get() = db.vaultDao()
    val gameDao get() = db.gameDao()
    val leagueGameDao get() = db.leagueGameDao()

    private val catalog: TeamCatalog by lazy {
        TeamCatalog.parse(
            context.applicationContext.assets.open("teams.json").bufferedReader().readText()
        )
    }

    private val playerCatalog: PlayerCatalog by lazy {
        PlayerCatalog.parse(
            context.applicationContext.assets.open("players.json").bufferedReader().readText()
        )
    }

    suspend fun arm(shieldId: Long, rescanActiveNotifications: Boolean = true) =
        armSession(shieldId, null, rescanActiveNotifications)

    suspend fun arm(shieldId: Long, hours: Int, rescanActiveNotifications: Boolean = true) =
        armSession(shieldId, hours, rescanActiveNotifications)

    private suspend fun armSession(shieldId: Long, hours: Int?, rescan: Boolean) {
        (context.applicationContext as? com.jessemaddox.spoileralert.SpoilerAlertApp)?.startupReady?.await()
        SessionEffects.mutex.withLock {
            when (shieldDao.byId(shieldId)?.kind) {
                "GAME" -> refreshGameShield(shieldId)
                "TEAM" -> refreshTeamShield(shieldId)
            }
            val transition = sessions.arm(shieldId, hours) ?: return@withLock
            transition.previous?.let { SessionActions.cancel(context, it) }
            scheduleSession(transition.current)
            sessions.reconcileEquivalentAttribution(shieldId)
        }
        SummaryNotifier.refresh(context)
        if (rescan) ActiveNotificationSweep.request()
    }

    /** The displayed generation is required; stale notification actions cannot extend a rearm. */
    suspend fun extend(token: SessionToken): Boolean {
        val changed = SessionEffects.mutex.withLock {
            val transition = sessions.extend(token) ?: return@withLock false
            SessionActions.cancel(context, token)
            scheduleSession(transition.current)
            true
        }
        if (changed) SummaryNotifier.refresh(context)
        return changed
    }

    private suspend fun scheduleSession(shield: ShieldEntity) {
        val token = SessionToken.from(shield) ?: return
        val now = System.currentTimeMillis()
        val deadline = ShieldCodec.sessionDeadlineMillis(shield) ?: return
        AutoDisarmWorker.schedule(context, token, deadline + ShieldCodec.GRACE_MS - now)
        CheckInWorker.schedule(context, token, (deadline - CheckIn.LEAD_TIME_MS - now).coerceAtLeast(0))
        eventCompletionTarget(shield.id)?.let {
            EventCompletionWorker.schedule(context, token, it.expectedEndMillis - now)
        }
    }

    suspend fun adjustDeadlineForDebug(token: SessionToken, armedAt: Long): SessionToken? =
        SessionEffects.mutex.withLock {
            val changed = sessions.adjustDeadlineForDebug(token, armedAt) ?: return@withLock null
            SessionActions.cancel(context, token)
            scheduleSession(changed.current)
            SessionToken.from(changed.current)
        }

    /** Recheck after any provider I/O before posting or scheduling local effects. */
    suspend fun withCurrentSession(token: SessionToken, action: suspend (ShieldEntity) -> Unit): Boolean =
        SessionEffects.mutex.withLock {
            val record = shieldDao.protectionRecords().firstOrNull { token.matches(it.shield) }
                ?.takeIf { it.isEffective(System.currentTimeMillis()) } ?: return@withLock false
            action(record.shield)
            true
        }

    /** Repair a process death between a DB commit and local effects without replacing running work. */
    suspend fun reconcileSessions() = SessionEffects.mutex.withLock {
        sessions.initialize()
        val wm = WorkManager.getInstance(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        for (shield in shieldDao.all()) {
            for (kind in listOf("auto-disarm", "checkin", "event-completion")) wm.cancelUniqueWork("$kind-${shield.id}")
            nm.cancel(AutoDisarmWorker.notificationId(shield.id))
            nm.cancel(CheckInWorker.notificationId(shield.id))
            nm.cancel(EventCompletionWorker.notificationId(shield.id))
            val token = SessionToken.from(shield) ?: continue
            if (shield.armed) {
                // Let an overdue worker commit expiry and post its result after startupReady.
                // Startup may itself have been caused by this running worker, so KEEP it.
                scheduleSession(shield)
            }
        }
        val now = System.currentTimeMillis()
        val activeRecords = shieldDao.protectionRecords().filter { it.isEffective(now) }
        val activeTags = activeRecords.mapTo(mutableSetOf()) { SessionActions.tag(SessionToken.from(it.shield)!!) }
        val allSessions = db.protectionSessionDao().all().associateBy { it.sessionId }
        val hidden = vaultDao.allHidden().associateBy { it.id }
        for (notice in nm.activeNotifications) {
            val tag = notice.tag
            when {
                tag?.startsWith("protection:") == true -> {
                    val sessionId = tag.removePrefix("protection:").substringBeforeLast(':')
                    val record = allSessions[sessionId]
                    val validEnded = notice.id == SessionActions.END_ID && record?.endReason == "EXPIRED" &&
                        record.revealAuthorizedAtMillis == null && hidden.values.any { it.ownerSessionId == sessionId }
                    if (tag !in activeTags && !validEnded) nm.cancel(tag, notice.id)
                }
                tag?.startsWith("vault:") == true -> {
                    val item = tag.removePrefix("vault:").toLongOrNull()?.let(hidden::get)
                    val owner = item?.ownerSessionId?.let(allSessions::get)
                    val activeOwner = activeRecords.any { it.session?.sessionId == owner?.sessionId } ||
                        (owner?.shieldKind == "FANTASY" && owner.endedAtMillis == null && activeRecords.isNotEmpty())
                    if (item == null || !activeOwner) nm.cancel(tag, notice.id)
                    else MaskedNotifier.reassign(context, item.id, item.shieldId, owner?.shieldKind != "FANTASY")
                }
                tag == null && notice.id in 20_000..39_999 -> nm.cancel(notice.id)
            }
        }
    }

    data class RevealOutcome(
        val name: String,
        val revealedCount: Int,
        val stillHiddenCount: Int = 0,
        val releasedSummary: WaitingSummary = WaitingSummary(0, 0, 0, 0),
        val retainedSummary: WaitingSummary = WaitingSummary(0, 0, 0, 0),
        val newerSessionStillActive: Boolean = false,
    )

    suspend fun revealScope(shieldId: Long): RevealSelection = sessions.revealScope(shieldId)

    /** Selection was captured by the UI or notification that offered the action. */
    suspend fun stopAndReveal(shieldId: Long, selection: RevealSelection, delete: Boolean = false,
        expectedCurrentSessionId: String? = null): RevealOutcome? = SessionEffects.mutex.withLock {
        val name = shieldDao.byId(shieldId)?.name
            ?: selection.sessionIds.firstNotNullOfOrNull { db.protectionSessionDao().byId(it)?.displayName }
            ?: return@withLock null
        val batch = sessions.reveal(selection, policy(), if (delete) shieldId else null, expectedCurrentSessionId)
        batch.ended.forEach { SessionActions.cancel(context, it) }
        // Ended sessions can retain a session-ended notice after expiry; clear only selected scopes.
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { notice -> selection.sessionIds.any { notice.tag?.startsWith("protection:$it:") == true } }
            .forEach { context.getSystemService(NotificationManager::class.java).cancel(it.tag, it.id) }
        CatchUpNotifier.post(context, shieldId, name, batch.released)
        MaskedNotifier.cancelAll(context, batch.released.map { it.id })
        batch.retained.forEach {
            MaskedNotifier.reassign(context, it.id, it.shieldId, shieldDao.byId(it.shieldId)?.kind != "FANTASY")
        }
        AppPrefs.setCompletedFirstCycle(context)
        cleanupGameShieldLocked(shieldId)
        val stillActive = shieldDao.protectionRecords().any {
            it.shield.id == shieldId && it.isEffective(System.currentTimeMillis())
        }
        RevealOutcome(name, batch.released.size, batch.retained.size,
            WaitingSummary.from(batch.released), WaitingSummary.from(batch.retained), stillActive)
    }

    /** Onboarding live-demo teardown (v3 amendment item 4): the demo temporarily armed the
     *  just-added team and let the interceptor hide one self-posted fake. Undo it cleanly so the
     *  user lands on a fresh, DISARMED team shield with nothing lingering — silently disarm (no
     *  reveal-to-history), cancel the demo's scheduled works + check-in, restore the default
     *  auto-disarm window (the demo shrank it to 1h), and hard-delete the demo vault row. */
    suspend fun teardownDemo(shieldId: Long, demoVaultRowId: Long?) = SessionEffects.mutex.withLock {
        shieldDao.byId(shieldId)?.let(SessionToken::from)?.let {
            sessions.close(it, "DEMO_ENDED")
            SessionActions.cancel(context, it)
        }
        shieldDao.setAutoDisarmHours(shieldId, AppPrefs.autoDisarmHours(context))
        demoVaultRowId?.let { deleteDemoVaultRow(it) }
    }

    /** Hard-delete only a row already validated by the onboarding demo's exact identity. */
    suspend fun deleteDemoVaultRow(rowId: Long) {
        MaskedNotifier.cancel(context, rowId)
        vaultDao.deleteById(rowId)
    }

    /**
     * Startup reconciliation invariant (ship-blocker fix): "no armed shield may exist before
     * onboarding completes." The onboarding live-demo ([ui.MainViewModel.runProtectionDemo])
     * temporarily arms the user's REAL team shield for a 1h window (shrinking autoDisarmHours
     * 4→1 and scheduling auto-disarm + check-in works) and lets the interceptor hide one
     * self-posted fake. Teardown normally runs from the in-VM `onFinish` lambda — but if the
     * process dies, the app is swiped from recents, or system Back is pressed mid-demo, that
     * in-memory teardown never fires, stranding: an armed shield (still hiding the new user's
     * REAL notifications for up to 1h), a persisted hidden fake vault row, and pending
     * WorkManager jobs.
     *
     * Called from [com.jessemaddox.spoileralert.SpoilerAlertApp.onCreate] on every process
     * start while still un-onboarded, this repairs all abandonment paths at once (Back, swipe,
     * process death) BEFORE any hiding can occur on the new process: disarm ALL shields, cancel
     * every `auto-disarm-<id>` / `checkin-<id>` unique work (plus their dangling notifications),
     * and delete the still-hidden self-posted fake.
     *
     * The real team shield is armed during the demo, so an actual team alert can also be caught.
     * Own-package rows are safe to delete because [InterceptorService] ignores every notification
     * from this app except debug-channel fakes. Genuine third-party rows stay sealed for the user
     * to reveal explicitly after onboarding.
     */
    suspend fun reconcileUnonboarded() = SessionEffects.mutex.withLock {
        for (shield in shieldDao.armed()) {
            SessionToken.from(shield)?.let {
                sessions.close(it, "ONBOARDING_ABANDONED")
                SessionActions.cancel(context, it)
            }
        }
        vaultDao.allHidden().filter { it.sourcePackage == context.packageName }
            .forEach { deleteDemoVaultRow(it.id) }
    }

    /**
     * Add (or reuse) a TEAM shield for a catalog team, deduped by [Team.id]. Re-completing
     * onboarding after an abandoned run would otherwise fork a DUPLICATE shield for the same
     * team (the old insert had no dedupe), so a brand-new user could end up with two shields for
     * one team. If a TEAM shield already exists for this catalog team we reuse it (returning its
     * id) rather than inserting a second. Wrapped in a transaction so a concurrent add of the
     * same team can't race two inserts. Returns the shield id.
     */
    suspend fun addTeamShield(team: Team): Long = db.withTransaction {
        shieldDao.byCatalogTeamId(team.id)?.id
            ?: shieldDao.insert(ShieldEntity(
                name = team.name,
                aliasesJson = ShieldCodec.encodeAliases(team.allAliases()),
                kind = "TEAM",
                catalogTeamId = team.id,
                autoDisarmHours = AppPrefs.autoDisarmHours(context),
            ))
    }

    /** What an unanswered-expiry disarm found: name for copy + how many items stay sealed. */
    data class DisarmOutcome(
        val name: String,
        val hiddenCount: Int,
        val waitingSummary: WaitingSummary = WaitingSummary(0, 0, 0, 0),
    )

    /** Expiry is an exact-generation transaction, followed by bounded local effects. */
    suspend fun expire(token: SessionToken,
        onExpired: suspend (DisarmOutcome, RevealSelection) -> Unit = { _, _ -> }): Boolean =
        SessionEffects.mutex.withLock {
            val selection = sessions.revealScope(token.shieldId)
            val expired = sessions.expire(token) ?: return@withLock false
            SessionActions.cancel(context, token, exceptWork = "auto-disarm")
            MaskedNotifier.cancelAll(context, expired.hidden.map { it.id })
            val outcome = DisarmOutcome(expired.shield.name, expired.hidden.size, WaitingSummary.from(expired.hidden))
            cleanupGameShieldLocked(token.shieldId, exceptWork = "auto-disarm")
            onExpired(outcome, selection)
            true
        }

    /** Automatic cleanup keeps durable session labels and all consent metadata. */
    suspend fun deleteShield(shieldId: Long) = SessionEffects.mutex.withLock { deleteShieldLocked(shieldId) }

    private suspend fun deleteShieldLocked(shieldId: Long, exceptWork: String? = null) {
        val removedToken = db.withTransaction {
            val current = shieldDao.byId(shieldId)?.takeIf { !it.armed } ?: return@withTransaction null
            val token = SessionToken.from(current)
            token?.let { sessions.close(it, "DELETED") }
            shieldDao.delete(shieldId)
            gameDao.deleteForShield(shieldId)
            token
        }
        removedToken?.let { SessionActions.cancel(context, it, exceptWork) }
    }

    /**
     * Session-scoped GAME shield for a matchup (v3 amendment item 2): named by the matchup
     * ("BUF @ KC"), kind GAME, aliases = the UNION of both mapped teams' catalog alias sets
     * (event-name fallback for unmapped sides — see [GameAliases.forMatchup]). Also caches
     * the matchup into the per-shield `games` table so pregame prompts and "Next:" lines work
     * unchanged, and enqueues the pregame ask for "Protect later" shields.
     *
     * Returns the shield id (an existing GAME shield for the same event is reused rather
     * than duplicated), or null when the matchup yields no usable aliases — the caller
     * should tell the user this one can't be protected yet.
     */
    suspend fun createGameShield(leagueGame: LeagueGameEntity): Long? {
        shieldDao.byGameEventId(leagueGame.eventId)?.let { existing ->
            // Reuse path (collated review finding 3): the shield already exists, but its
            // per-shield `games` row and pregame-prompt work may be long gone — e.g. a prior
            // session ended with revealed vault rows, which drops the games row
            // ([cleanupGameShield]) without deleting the shield itself. Re-provision both so
            // reusing behaves exactly like a fresh create: the pending card reappears on
            // Home (games row present ⇒ [GameShieldLifecycle.showOnHome]'s hasPendingGame),
            // and the prompt fires again ~10 minutes before kickoff.
            gameDao.upsertAll(listOf(gameEntityFor(leagueGame, existing.id)))
            PregamePromptWorker.enqueue(
                context, leagueGame.eventId, existing.id, leagueGame.startMillis, leagueGame.completed,
            )
            return existing.id
        }
        val aliases = GameAliases.forMatchup(
            catalog, leagueGame.leagueId, leagueGame.homeEspnId, leagueGame.awayEspnId,
            leagueGame.name, playerCatalog,
        ) ?: return null
        val id = db.withTransaction {
            // The optimistic lookup above handles the common reuse path. Recheck under the
            // serialized Room transaction so rapid double taps cannot both insert this event.
            val id = shieldDao.byGameEventId(leagueGame.eventId)?.id
                ?: shieldDao.insert(ShieldEntity(
                    name = leagueGame.shortName,
                    aliasesJson = ShieldCodec.encodeAliases(aliases),
                    kind = "GAME",
                    gameEventId = leagueGame.eventId,
                    autoDisarmHours = SessionDurations.suggestedHours(leagueGame.leagueId),
                ))
            gameDao.upsertAll(listOf(gameEntityFor(leagueGame, id)))
            id
        }
        PregamePromptWorker.enqueue(
            context, leagueGame.eventId, id, leagueGame.startMillis, leagueGame.completed,
        )
        return id
    }

    private fun gameEntityFor(leagueGame: LeagueGameEntity, shieldId: Long) = GameEntity(
        id = leagueGame.eventId, shieldId = shieldId, name = leagueGame.name,
        shortName = leagueGame.shortName, startMillis = leagueGame.startMillis,
        completed = leagueGame.completed, fetchedAtMillis = leagueGame.fetchedAtMillis,
    )

    /**
     * Re-derive a GAME shield's name/aliases/cached game row at USE TIME (collated review
     * finding 2): [createGameShield] snapshots these once and never refreshes them, so a
     * schedule correction (kickoff moved, an alias mapping fixed) leaves the shield stale for
     * its whole life. Re-reads the league_games row for this shield's event and, if the pure
     * [GameShieldRefresh.diff] says anything actually changed, updates the shield's
     * name/aliasesJson (narrow column update — see [ShieldDao.updateGameShieldDerived]) and
     * upserts its per-shield games row.
     *
     * Deliberately updates even an ARMED shield — fresher names/aliases are strictly better
     * mid-session — but never touches armed/armedAtMillis; those are the disarm/extend/grace
     * CAS fields and this path has no business with them.
     *
     * No-op when the shield is missing, isn't a GAME shield, has no gameEventId, the league
     * game is no longer cached (pruned/finished), or its aliases no longer derive (matchup
     * data withdrawn) — in all those cases the shield keeps whatever it last had.
     */
    suspend fun refreshGameShield(shieldId: Long) {
        val s = shieldDao.byId(shieldId) ?: return
        if (s.kind != "GAME") return
        val eventId = s.gameEventId ?: return
        val leagueGame = leagueGameDao.byId(eventId) ?: return
        val aliases = GameAliases.forMatchup(
            catalog, leagueGame.leagueId, leagueGame.homeEspnId, leagueGame.awayEspnId,
            leagueGame.name, playerCatalog,
        ) ?: return
        val cachedGame = gameDao.byKey(eventId, shieldId)
        val diff = GameShieldRefresh.diff(
            currentName = s.name,
            currentAliasesJson = s.aliasesJson,
            currentStartMillis = cachedGame?.startMillis,
            freshName = leagueGame.shortName,
            freshAliasesJson = ShieldCodec.encodeAliases(aliases),
            freshStartMillis = leagueGame.startMillis,
        )
        if (!diff.changed) return
        db.withTransaction {
            shieldDao.updateGameShieldDerived(shieldId, diff.name, diff.aliasesJson)
            gameDao.upsertAll(listOf(gameEntityFor(leagueGame, shieldId)))
        }
    }

    /**
     * Re-derive a TEAM shield's aliases at USE TIME: unions the catalog team's own aliases with
     * its bundled star-player/coach aliases ([PlayerCatalog]), so "bijan"/"penix" catch a Falcons
     * spoiler even when the team name is absent. Team aliases come first, so a colliding player
     * alias never demotes a team flag. Narrow aliases-only column update — never touches
     * armed/armedAtMillis, matching [refreshGameShield]'s CAS safety. Writes only when the encoded
     * JSON actually changes. No-op for non-TEAM kinds or unknown catalog teams (custom-name shields).
     */
    suspend fun refreshTeamShield(shieldId: Long) {
        val s = shieldDao.byId(shieldId) ?: return
        if (s.kind != "TEAM") return
        val team = catalog.teamById(s.catalogTeamId) ?: return
        val aliases = (team.allAliases() + playerCatalog.aliasesForTeam(team.id)).distinctBy { it.text }
        val json = ShieldCodec.encodeAliases(aliases)
        if (json == s.aliasesJson) return
        shieldDao.updateShieldAliases(shieldId, json)
    }

    /** Resolve the public event a live protection belongs to, without reading vault content. */
    suspend fun eventCompletionTarget(shieldId: Long): EventCompletionTarget? {
        val shield = shieldDao.byId(shieldId)?.takeIf { it.armed } ?: return null
        val game = shield.gameEventId?.let { eventId ->
            leagueGameDao.byId(eventId)?.let { league ->
                Triple(league.eventId, league.leagueId, league.startMillis) to league.endMillis
            }
        } ?: gameDao.upcomingForShield(
            shieldId,
            GameWindows.sinceMillis(System.currentTimeMillis()),
        ).firstOrNull()?.let { cached ->
            val league = leagueGameDao.byId(cached.id)
            Triple(cached.id, league?.leagueId ?: catalog.leagueIdForTeam(shield.catalogTeamId), cached.startMillis) to
                league?.endMillis
        } ?: return null
        val (identity, explicitEnd) = game
        val leagueId = identity.second ?: return null
        val expectedEnd = explicitEnd ?: identity.third +
            SessionDurations.typicalGameLengthMinutes(leagueId) * 60_000L
        val deadline = ShieldCodec.expiresAtMillis(shield) ?: return null
        return EventCompletionTarget(
            shieldId,
            shield.name,
            leagueId,
            identity.first,
            identity.third,
            expectedEnd,
            deadline,
        )
    }

    /**
     * Session-scoped cleanup for GAME shields, run after every reveal path (see
     * [GameShieldLifecycle]): a GAME shield that ends disarmed with no vault rows at all is
     * deleted on the spot; one with revealed-only rows is retained (Home hides it) until
     * [pruneOrphanedGameShields] catches it after history retention clears its rows.
     */
    suspend fun cleanupGameShield(shieldId: Long) = SessionEffects.mutex.withLock { cleanupGameShieldLocked(shieldId) }

    private suspend fun cleanupGameShieldLocked(shieldId: Long, exceptWork: String? = null) {
        val s = shieldDao.byId(shieldId) ?: return
        if (s.kind != "GAME" || s.armed) return
        val hidden = vaultDao.hiddenForShield(shieldId).size
        val total = vaultDao.countForShield(shieldId)
        if (GameShieldLifecycle.deleteNowAfterReveal(s.kind, s.armed, hidden, total)) {
            deleteShieldLocked(shieldId, exceptWork)
        } else if (hidden == 0) {
            // Spent, but revealed vault rows still render it: keep the shield (Home hides
            // it) and drop only its cached game, so the matchup returns to the discovery
            // pool and no "Next:"/widget surface keeps advertising a finished session.
            // With the games row gone, the orphan prune picks the shield up as soon as
            // history retention clears its remaining rows.
            gameDao.deleteForShield(shieldId)
        }
    }

    /** Extension of the history prune: delete spent GAME shields nothing references
     *  anymore (no vault rows and no recent cached game). */
    suspend fun pruneOrphanedGameShields(nowMillis: Long) = SessionEffects.mutex.withLock {
        db.withTransaction {
            shieldDao.orphanedGameShieldIds(nowMillis - ORPHAN_GAME_WINDOW_MS)
                .forEach { deleteShieldLocked(it) }
        }
    }

    /** Reveal one vault item and clear its masked stand-in if any. */
    suspend fun revealItem(itemId: Long) = SessionEffects.mutex.withLock {
        val item = vaultDao.byId(itemId)
        vaultDao.reveal(itemId, System.currentTimeMillis())
        MaskedNotifier.cancel(context, itemId)
        item?.let { cleanupGameShieldLocked(it.shieldId) }
    }

    suspend fun clearRevealedHistory(): Int {
        val deleted = vaultDao.deleteRevealed()
        pruneOrphanedGameShields(System.currentTimeMillis())
        return deleted
    }

    /**
     * The fantasy sentinel shield id (v3 amendment item 3): a single persistent kind=FANTASY
     * row that owns every fantasy-app vault row, created lazily on the first fantasy-app hide.
     * It is deliberately never armed — package-level hides don't need a matcher/expiry, they
     * just need somewhere in the vault to attribute to (see [InterceptorService]'s doc for why
     * arming it in lockstep with real shields was rejected as unnecessary extra state). It
     * never appears on Home ([GameShieldLifecycle.showOnHome]) and is never auto-deleted by any
     * prune (all of those are scoped to kind = 'GAME'). Wrapped in a transaction so concurrent
     * fantasy hides racing to create it can't produce two sentinel rows.
     */
    suspend fun fantasyShieldId(): Long = db.withTransaction {
        shieldDao.fantasyShield()?.id ?: shieldDao.insert(
            ShieldEntity(
                name = "Fantasy",
                aliasesJson = ShieldCodec.encodeAliases(emptyList()),
                kind = "FANTASY",
                maskedAlerts = false,
                pregamePrompts = false,
            )
        )
    }

    /** Persist the immutable callback identity. No lookup can substitute a newer session. */
    suspend fun upsertVaultHidden(
        key: String, shieldId: Long, sourcePackage: String, sourceAppLabel: String,
        title: String, text: String, postedAt: Long, conversation: String?,
        messageCount: Int = 1, isConversation: Boolean = false,
        isImportantConversation: Boolean = false, sourceCategory: String? = null,
        hasExactOpen: Boolean = false, hasReplyAction: Boolean = false,
        hasMarkUnreadAction: Boolean = false, capture: CaptureContext,
        afterPersist: suspend (VaultEntity) -> Unit = {},
    ): Long = SessionEffects.mutex.withLock {
        require(shieldId == capture.shieldId)
        val result = sessions.persist(capture, VaultEntity(
            shieldId = shieldId, sourcePackage = sourcePackage, sourceAppLabel = sourceAppLabel,
            title = title, text = text, postedAtMillis = postedAt, notificationKey = key,
            conversation = conversation, messageCount = messageCount, isConversation = isConversation,
            isImportantConversation = isImportantConversation, sourceCategory = sourceCategory,
            hasExactOpen = hasExactOpen, hasReplyAction = hasReplyAction, hasMarkUnreadAction = hasMarkUnreadAction,
        ), policy())
        if (result.inserted) AppPrefs.incrementLifetimeHidden(context)
        afterPersist(result.item)
        result.item.id
    }

    suspend fun reconcileEquivalentHiddenAttribution(targetShieldId: Long? = null) =
        SessionEffects.mutex.withLock { sessions.reconcileEquivalentAttribution(targetShieldId) }

    companion object {
        /** How long a spent GAME shield's cached game keeps it alive for the orphan prune. */
        val ORPHAN_GAME_WINDOW_MS = TimeUnit.DAYS.toMillis(7)
    }
}
