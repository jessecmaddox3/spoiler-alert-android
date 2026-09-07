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

    suspend fun arm(shieldId: Long, rescanActiveNotifications: Boolean = true) {
        val s = shieldDao.byId(shieldId) ?: return
        // Arming is the moment aliases matter most: re-derive them from the freshest catalog data
        // (kickoff moves, mapping fixes, newly bundled players) before the session starts. Neither
        // refresh touches armed/armedAtMillis, so the scheduling below still uses this snapshot's
        // autoDisarmHours. No-op for CUSTOM/FANTASY shields.
        when (s.kind) {
            "GAME" -> refreshGameShield(shieldId)
            "TEAM" -> refreshTeamShield(shieldId)
        }
        val wm = WorkManager.getInstance(context)
        val sessionMs = TimeUnit.HOURS.toMillis(s.autoDisarmHours.toLong())
        // Capture the session start before scheduling, then persist this exact value below.
        // WorkManager's delay therefore cannot mature before the derived Room deadline merely
        // because setArmed happened a few milliseconds after enqueueUniqueWork.
        val armedAt = System.currentTimeMillis()
        wm.enqueueUniqueWork(
            "auto-disarm-$shieldId",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoDisarmWorker>()
                // Seal at the absolute end of grace. Interception uses this same derived
                // deadline, so a delayed WorkManager firing can never create a leak window.
                .setInitialDelay(sessionMs + ShieldCodec.GRACE_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(AutoDisarmWorker.KEY_SHIELD_ID to shieldId))
                .build(),
        )
        // Check in 15 minutes before the selected session window ends, unless the whole session is
        // too short for the lead time to make sense.
        val sessionMinutes = s.autoDisarmHours * 60L
        if (TimeUnit.MINUTES.toMillis(sessionMinutes) > CheckIn.WINDOW_MS) {
            wm.enqueueUniqueWork(
                "checkin-$shieldId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CheckInWorker>()
                    .setInitialDelay(sessionMinutes - 15, TimeUnit.MINUTES)
                    .setInputData(workDataOf(CheckInWorker.KEY_SHIELD_ID to shieldId))
                    .build(),
            )
        }
        shieldDao.setArmed(shieldId, armed = true, armedAt = armedAt)
        eventCompletionTarget(shieldId)?.let { target ->
            EventCompletionWorker.schedule(context, shieldId, target.expectedEndMillis - armedAt)
        }
        reconcileEquivalentHiddenAttribution(shieldId)
        // Session lifecycle surface appears (silently) the moment protection turns on.
        SummaryNotifier.refresh(context)
        if (rescanActiveNotifications) ActiveNotificationSweep.request()
    }

    /** Arm for a chosen session length (session sheet): persist the hours first so the
     *  auto-disarm/check-in scheduling in [arm] (which re-reads the entity) uses them. The base
     *  [arm] re-derives GAME/TEAM aliases itself, so no separate refresh is needed here. */
    suspend fun arm(
        shieldId: Long,
        hours: Int,
        rescanActiveNotifications: Boolean = true,
    ) {
        shieldDao.setAutoDisarmHours(shieldId, hours.coerceIn(1, 72))
        arm(shieldId, rescanActiveNotifications)
    }

    /** Add exactly one hour to the current selected deadline. This does not rescan notifications
     * or restart the original multi-hour duration from now. */
    suspend fun extend(shieldId: Long): Boolean {
        val now = System.currentTimeMillis()
        val plan = SessionExtension.plan(shieldDao.byId(shieldId), now) ?: return false
        if (shieldDao.extendIfArmedAt(
                id = shieldId,
                expectedArmedAt = plan.expectedArmedAtMillis,
                newArmedAt = plan.newArmedAtMillis,
            ) == 0
        ) return false

        val wm = WorkManager.getInstance(context)
        AutoDisarmWorker.schedule(
            context,
            shieldId,
            plan.sessionDeadlineMillis + ShieldCodec.GRACE_MS - now,
        )
        val checkInDelay = plan.sessionDeadlineMillis - now - CheckIn.LEAD_TIME_MS
        if (checkInDelay > 0) {
            wm.enqueueUniqueWork(
                "checkin-$shieldId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CheckInWorker>()
                    .setInitialDelay(checkInDelay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(CheckInWorker.KEY_SHIELD_ID to shieldId))
                    .build(),
            )
        } else {
            wm.cancelUniqueWork("checkin-$shieldId")
        }
        CheckInWorker.cancelNotification(context, shieldId)
        eventCompletionTarget(shieldId)?.let { target ->
            EventCompletionWorker.schedule(context, shieldId, target.expectedEndMillis - now)
        }
        SummaryNotifier.refresh(context)
        return true
    }

    data class RevealOutcome(
        val name: String,
        val revealedCount: Int,
        val stillHiddenCount: Int = 0,
        val releasedSummary: WaitingSummary = WaitingSummary(0, 0, 0, 0),
        val retainedSummary: WaitingSummary = WaitingSummary(0, 0, 0, 0),
    )

    private data class ReleaseBatch(
        val released: List<VaultEntity>,
        val retained: List<Pair<VaultEntity, Long>>,
    )

    /** Explicit vault reveal: the user chose to expose this group's contents. */
    suspend fun disarmAndReveal(shieldId: Long): RevealOutcome? =
        release(shieldId, respectRemainingProtections = true)

    /** Home's Stop & reveal: release this event without exposing content still covered by a
     * different active protection. If this was the final session, release fantasy-app items too. */
    suspend fun stopAndReveal(shieldId: Long): RevealOutcome? =
        release(shieldId, respectRemainingProtections = true)

    private suspend fun release(
        shieldId: Long,
        respectRemainingProtections: Boolean,
    ): RevealOutcome? {
        val s = shieldDao.byId(shieldId) ?: return null
        val excludedPackages = AppPrefs.excludedPackages(context)
        val batch = db.withTransaction {
            val targetItems = vaultDao.hiddenForShield(shieldId)
            shieldDao.setArmed(shieldId, armed = false, armedAt = null)
            val now = System.currentTimeMillis()
            val remainingEntities = shieldDao.armed().filter {
                (ShieldCodec.expiresAtMillis(it) ?: Long.MIN_VALUE) > now
            }
            val remainingShields = remainingEntities.mapNotNull {
                runCatching { ShieldCodec.toArmedShield(it) }.getOrNull()
            }

            // A row can match two active protections even though interception assigns it to one.
            // Never reveal it while another shield still covers the same spoiler; transfer its
            // ownership so that remaining protection keeps its promise.
            val retained = if (respectRemainingProtections) targetItems.mapNotNull { item ->
                val hit = Matcher.match(
                    "${item.title}\n${item.text}",
                    remainingShields,
                    SourcePolicy.modeFor(item.sourcePackage, excludedPackages),
                ) ?: return@mapNotNull null
                vaultDao.reattributeHidden(item.id, hit.id)
                item to hit.id
            } else emptyList()
            val retainedIds = retained.mapTo(mutableSetOf()) { it.first.id }
            val released = targetItems.filter { it.id !in retainedIds }
            released.forEach { vaultDao.reveal(it.id, now) }

            val fantasyItems = if (s.kind != "FANTASY" && remainingEntities.isEmpty()) {
                shieldDao.fantasyShield()?.let { fantasy ->
                    vaultDao.hiddenForShield(fantasy.id).also {
                        vaultDao.revealAllForShield(fantasy.id, now)
                    }
                }.orEmpty()
            } else emptyList()
            ReleaseBatch(released + fantasyItems, retained)
        }
        if (!s.armed && batch.released.isEmpty() && batch.retained.isEmpty()) return null
        WorkManager.getInstance(context).cancelUniqueWork("auto-disarm-$shieldId")
        WorkManager.getInstance(context).cancelUniqueWork("checkin-$shieldId")
        CheckInWorker.cancelNotification(context, shieldId)
        EventCompletionWorker.cancel(context, shieldId)
        CatchUpNotifier.post(context, shieldId, s.name, batch.released)
        MaskedNotifier.cancelAll(context, batch.released.map { it.id })
        batch.retained.forEach { (item, newShieldId) ->
            MaskedNotifier.reassign(context, item.id, newShieldId)
        }
        // The session-ended notification is now satisfied.
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(AutoDisarmWorker.notificationId(shieldId))
        // First completed hide → reveal cycle: Home's teaching steps strip retires.
        AppPrefs.setCompletedFirstCycle(context)
        // GAME shields are session-scoped: this reveal may have spent the shield.
        cleanupGameShield(shieldId)
        return RevealOutcome(
            s.name,
            batch.released.size,
            batch.retained.size,
            WaitingSummary.from(batch.released),
            WaitingSummary.from(batch.retained.map { it.first }),
        )
    }

    /** Onboarding live-demo teardown (v3 amendment item 4): the demo temporarily armed the
     *  just-added team and let the interceptor hide one self-posted fake. Undo it cleanly so the
     *  user lands on a fresh, DISARMED team shield with nothing lingering — silently disarm (no
     *  reveal-to-history), cancel the demo's scheduled works + check-in, restore the default
     *  auto-disarm window (the demo shrank it to 1h), and hard-delete the demo vault row. */
    suspend fun teardownDemo(shieldId: Long, demoVaultRowId: Long?) {
        shieldDao.setArmed(shieldId, armed = false, armedAt = null)
        shieldDao.setAutoDisarmHours(shieldId, AppPrefs.autoDisarmHours(context))
        WorkManager.getInstance(context).cancelUniqueWork("auto-disarm-$shieldId")
        WorkManager.getInstance(context).cancelUniqueWork("checkin-$shieldId")
        CheckInWorker.cancelNotification(context, shieldId)
        EventCompletionWorker.cancel(context, shieldId)
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
    suspend fun reconcileUnonboarded() {
        val armed = shieldDao.armed()
        val wm = WorkManager.getInstance(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        armed.forEach { s ->
            wm.cancelUniqueWork("auto-disarm-${s.id}")
            wm.cancelUniqueWork("checkin-${s.id}")
            CheckInWorker.cancelNotification(context, s.id)
            EventCompletionWorker.cancel(context, s.id)
            nm?.cancel(AutoDisarmWorker.notificationId(s.id))
        }
        shieldDao.disarmAll()
        vaultDao.allHidden()
            .filter { it.sourcePackage == context.packageName }
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

    /** Auto-disarm path — the CONSENT fix: on unanswered expiry we stop intercepting but KEEP
     *  the vault sealed. Nothing is ever revealed without an explicit user action.
     *
     *  Same compare-and-swap semantics as the old disarm-and-reveal path (see the ordering
     *  analysis on [disarmIfArmedAt][ShieldDao.disarmIfArmedAt]): disarms only if the session
     *  the worker observed (armedAt) is still the live one — a concurrent "Keep hiding" extend
     *  changes armedAtMillis and must win, in which case this returns null and touches nothing.
     *
     *  On success: cancels the auto-disarm + check-in works, clears the dangling check-in
     *  notification, and cancels the masked stand-ins (session over — the tray quiets down;
     *  the items remain sealed in-app and the session-ended notification is the pointer). */
    suspend fun disarmIfUnchangedKeepSealed(shieldId: Long, expectedArmedAt: Long): DisarmOutcome? {
        val s = shieldDao.byId(shieldId) ?: return null
        val hiddenItems = vaultDao.hiddenForShield(shieldId)
        val hiddenIds = hiddenItems.map { it.id }
        if (shieldDao.disarmIfArmedAt(shieldId, expectedArmedAt) == 0) return null
        WorkManager.getInstance(context).cancelUniqueWork("auto-disarm-$shieldId")
        WorkManager.getInstance(context).cancelUniqueWork("checkin-$shieldId")
        CheckInWorker.cancelNotification(context, shieldId)
        EventCompletionWorker.cancel(context, shieldId)
        MaskedNotifier.cancelAll(context, hiddenIds)
        return DisarmOutcome(s.name, hiddenIds.size, WaitingSummary.from(hiddenItems))
    }

    /** Delete a shield plus its cached games (vault rows are kept and revealed by the caller). */
    suspend fun deleteShield(shieldId: Long) {
        EventCompletionWorker.cancel(context, shieldId)
        db.withTransaction {
            shieldDao.delete(shieldId)
            gameDao.deleteForShield(shieldId)
        }
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
    suspend fun cleanupGameShield(shieldId: Long) {
        val s = shieldDao.byId(shieldId) ?: return
        if (s.kind != "GAME" || s.armed) return
        val hidden = vaultDao.hiddenForShield(shieldId).size
        val total = vaultDao.countForShield(shieldId)
        if (GameShieldLifecycle.deleteNowAfterReveal(s.kind, s.armed, hidden, total)) {
            deleteShield(shieldId)
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
    suspend fun pruneOrphanedGameShields(nowMillis: Long) {
        shieldDao.orphanedGameShieldIds(nowMillis - ORPHAN_GAME_WINDOW_MS)
            .forEach { deleteShield(it) }
    }

    /** Reveal one vault item and clear its masked stand-in if any. */
    suspend fun revealItem(itemId: Long) {
        val item = vaultDao.byId(itemId)
        vaultDao.reveal(itemId, System.currentTimeMillis())
        MaskedNotifier.cancel(context, itemId)
        item?.let { cleanupGameShield(it.shieldId) }
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

    /** Atomically refresh-or-create the hidden vault row for a notification key.
     *  Returns the affected row's id. */
    suspend fun upsertVaultHidden(
        key: String, shieldId: Long, sourcePackage: String, sourceAppLabel: String,
        title: String, text: String, postedAt: Long, conversation: String?,
        messageCount: Int = 1,
        isConversation: Boolean = false,
        isImportantConversation: Boolean = false,
        sourceCategory: String? = null,
        hasExactOpen: Boolean = false,
        hasReplyAction: Boolean = false,
        hasMarkUnreadAction: Boolean = false,
    ): Long? = db.withTransaction {
        val now = System.currentTimeMillis()
        val owner = shieldDao.byId(shieldId)
        val activeEntities = shieldDao.armed().filter {
            (ShieldCodec.expiresAtMillis(it) ?: Long.MIN_VALUE) > now
        }
        val ownerStillActive = activeEntities.any { it.id == shieldId }
        val alternateHit = if (!ownerStillActive && owner?.kind != "FANTASY") {
            Matcher.match(
                "$title\n$text",
                activeEntities.mapNotNull {
                    runCatching { ShieldCodec.toArmedShield(it) }.getOrNull()
                },
                SourcePolicy.modeFor(sourcePackage, AppPrefs.excludedPackages(context)),
            )
        } else null
        val protectedShieldId = when {
            owner?.kind == "FANTASY" && activeEntities.isNotEmpty() -> shieldId
            ownerStillActive -> shieldId
            alternateHit != null -> alternateHit.id
            else -> null
        }

        // Stop & reveal and this write are both Room transactions. Whichever commits second sees
        // the other's authoritative state: if Stop won, a listener callback already in flight
        // must preserve the canceled source notification as revealed catch-up content, never
        // create a newly sealed row after the user was told everything had been released.
        if (protectedShieldId == null) {
            vaultDao.latestByNotificationKey(key)?.let { existing ->
                vaultDao.updateContentById(
                    existing.id, shieldId, sourcePackage, sourceAppLabel,
                    title, text, postedAt, key, conversation, messageCount, isConversation,
                    isImportantConversation, sourceCategory, hasExactOpen, hasReplyAction,
                    hasMarkUnreadAction,
                )
                return@withTransaction existing.id
            }
            AppPrefs.incrementLifetimeHidden(context)
            return@withTransaction vaultDao.insert(VaultEntity(
                shieldId = shieldId, sourcePackage = sourcePackage,
                sourceAppLabel = sourceAppLabel, title = title, text = text,
                postedAtMillis = postedAt, notificationKey = key,
                revealedAtMillis = now, conversation = conversation,
                messageCount = messageCount, isConversation = isConversation,
                isImportantConversation = isImportantConversation, sourceCategory = sourceCategory,
                hasExactOpen = hasExactOpen, hasReplyAction = hasReplyAction,
                hasMarkUnreadAction = hasMarkUnreadAction,
            ))
        }

        if (vaultDao.updateHiddenByKey(
                key, protectedShieldId, sourcePackage, sourceAppLabel,
                title, text, postedAt, conversation, messageCount, isConversation,
                isImportantConversation, sourceCategory, hasExactOpen, hasReplyAction,
                hasMarkUnreadAction,
            ) > 0
        ) return@withTransaction vaultDao.hiddenIdByKey(key)

        // WhatsApp and similar apps can rotate notification keys as one chat accumulates
        // messages. Fold those updates into the existing conversation row, merging unique
        // message lines so the vault neither repeats cumulative snapshots nor loses earlier text.
        val existingConversation = conversation
            ?.takeIf { isConversation && it.isNotBlank() }
            ?.let { vaultDao.hiddenConversation(protectedShieldId, sourcePackage, it) }
        if (existingConversation != null) {
            AppPrefs.incrementLifetimeHidden(context)
            val mergedText = ConversationUpdates.mergeText(
                existingConversation.text, text, existingConversation.messageCount, messageCount,
            )
            vaultDao.updateContentById(
                existingConversation.id, protectedShieldId, sourcePackage, sourceAppLabel,
                title, mergedText, postedAt, key, conversation,
                ConversationUpdates.mergedMessageCount(existingConversation, messageCount, text),
                isConversation, isImportantConversation, sourceCategory, hasExactOpen,
                hasReplyAction, hasMarkUnreadAction,
            )
            return@withTransaction existingConversation.id
        }

        // Genuinely new hide (not a re-post of an already-hidden key): bump the lifetime tally.
        AppPrefs.incrementLifetimeHidden(context)
        vaultDao.insert(VaultEntity(
            shieldId = protectedShieldId, sourcePackage = sourcePackage,
            sourceAppLabel = sourceAppLabel,
            title = title, text = text, postedAtMillis = postedAt, notificationKey = key,
            conversation = conversation,
            messageCount = messageCount, isConversation = isConversation,
            isImportantConversation = isImportantConversation, sourceCategory = sourceCategory,
            hasExactOpen = hasExactOpen, hasReplyAction = hasReplyAction,
            hasMarkUnreadAction = hasMarkUnreadAction,
        ))
    }

    /** Move still-hidden rows from an equivalent legacy interest shield to the active GAME
     * shield. This repairs The Open Championship/The Open split without touching revealed data. */
    suspend fun reconcileEquivalentHiddenAttribution(targetShieldId: Long? = null) {
        val allShields = shieldDao.all()
        val byId = allShields.associateBy { it.id }
        val targets = allShields
            .filter { it.armed && (targetShieldId == null || it.id == targetShieldId) }
            // On upgrade, a legacy interest and its newer event shield can both still be armed.
            // The session-scoped GAME shield owns the active-event Home card and its count.
            .sortedWith(
                compareByDescending<ShieldEntity> { it.kind == "GAME" }
                    .thenByDescending { it.armedAtMillis ?: Long.MIN_VALUE }
            )
        if (targets.isEmpty()) return
        vaultDao.allHidden().forEach { item ->
            val source = byId[item.shieldId] ?: return@forEach
            val target = targets.firstOrNull {
                EventIdentity.canonical(it.name).key == EventIdentity.canonical(source.name).key
            } ?: return@forEach
            if (target.id != item.shieldId) vaultDao.reattributeHidden(item.id, target.id)
        }
    }

    companion object {
        /** How long a spent GAME shield's cached game keeps it alive for the orphan prune. */
        val ORPHAN_GAME_WINDOW_MS = TimeUnit.DAYS.toMillis(7)
    }
}
