package com.jessemaddox.spoileralert.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jessemaddox.spoileralert.data.GameWindows
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.data.VaultRetention
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.domain.Alias
import com.jessemaddox.spoileralert.domain.Team
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.schedule.ScheduleRefreshWorker
import com.jessemaddox.spoileralert.schedule.LEAGUE_SCOREBOARDS
import com.jessemaddox.spoileralert.schedule.LiveScoreFetcher
import com.jessemaddox.spoileralert.schedule.ScoreAnswer
import com.jessemaddox.spoileralert.schedule.ScoreCheckpoint
import com.jessemaddox.spoileralert.schedule.ScoreQuestion
import com.jessemaddox.spoileralert.schedule.SkipAnswer
import com.jessemaddox.spoileralert.schedule.SpoilerFreeScore
import com.jessemaddox.spoileralert.service.SummaryNotifier
import com.jessemaddox.spoileralert.widget.SpoilerTileService
import com.jessemaddox.spoileralert.widget.WidgetRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/** Health of the protection pipeline, driving the Home banner (primary failure signal). */
data class ProtectionStatus(
    val listenerEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val serviceConnected: Boolean = true,
)

/** One event's current spoiler-free lookup. Raw scoreboard values never enter UI state. */
data class ScoreCheckState(
    val question: ScoreQuestion,
    val loading: Boolean,
    val answer: ScoreAnswer? = null,
    val detail: String? = null,
    val checkedAtMillis: Long? = null,
    /** True when no fresh public scoreboard snapshot could be obtained. */
    val sourceUnavailable: Boolean = false,
)

/** Only neutral game positions and categorical answers are allowed into UI state. */
data class GameTimelineState(
    val loading: Boolean = false,
    val checkpoints: List<ScoreCheckpoint> = emptyList(),
    val sourceUnavailable: Boolean = false,
    val historicalLoading: Boolean = false,
    val historicalCheckpointId: String? = null,
    val historicalAnswer: ScoreCheckState? = null,
    val skipLoading: Boolean = false,
    val skipStartId: String? = null,
    val skipEndId: String? = null,
    val skipAnswer: SkipAnswer? = null,
    val skipCheckedAtMillis: Long? = null,
)

/** Onboarding live-demo phases (v3 amendment item 4). Drives the "See it work" step. */
sealed interface DemoPhase {
    /** Not started, or reset. */
    object Idle : DemoPhase
    /** Fake posted; waiting for the interceptor to catch and vault it. */
    object Running : DemoPhase
    /** The interceptor hid it — sitting in the vault as content-free "message hidden". */
    data class Hidden(val vaultRowId: Long, val sourceLabel: String) : DemoPhase
    /** User tapped Reveal — the real (fake) content is now shown. */
    data class Revealed(val title: String, val text: String) : DemoPhase
    /** Interceptor didn't catch it in time (listener not connected / permission off). */
    object Failed : DemoPhase
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ShieldRepository(app)
    private val liveScoreFetcher = LiveScoreFetcher()

    val shields: Flow<List<ShieldEntity>> = repo.shieldDao.observeAll()
    val hiddenItems: Flow<List<VaultEntity>> = repo.vaultDao.observeHidden()
    val allVaultItems: Flow<List<VaultEntity>> = repo.vaultDao.observeAll()

    private val nowAtInit = System.currentTimeMillis()
    private val currentTime = MutableStateFlow(nowAtInit)
    private val recentShieldGames: Flow<List<GameEntity>> =
        repo.gameDao.observeSince(GameWindows.sinceMillis(nowAtInit))
    private val recentProtectedGames: Flow<List<GameEntity>> =
        repo.gameDao.observeSinceIncludingCompleted(GameWindows.sinceMillis(nowAtInit))

    /** Earliest upcoming (or recently started — see [GameWindows]) cached game per shield,
     *  for the "Next: …" line on shield cards AND [hasPendingGame]-style checks (a shield's
     *  pending-game card must not vanish just because kickoff passed; see collated review
     *  finding 1 / MainActivity's `hasPendingGame`). */
    val nextGames: Flow<Map<Long, GameEntity>> =
        recentShieldGames
            .map { games -> games.groupBy { it.shieldId }.mapValues { (_, g) -> g.minBy { it.startMillis } } }

    /** Cached games per shield with a look-back, so an in-progress matchup can name its
     *  session ("PROTECTED · BUF @ KC") — see [MatchupNames]. */
    val sessionGames: Flow<Map<Long, List<GameEntity>>> =
        recentShieldGames
            .map { games -> games.groupBy { it.shieldId } }

    /** League-wide discovery pool (soonest first, including recently-started games — see
     *  [GameWindows]) and the events the user already covers. */
    private val leaguePool: Flow<List<LeagueGameEntity>> =
        repo.leagueGameDao.observeUpcoming(null, GameWindows.sinceMillis(nowAtInit), 400)
    private val coveredEventIds: Flow<Set<String>> =
        recentShieldGames.map { games -> games.map { it.id }.toSet() }

    /** Home's intentionally small daily feed. The full pool remains behind Find an event. */
    val dailyFeed: Flow<DailyEventFeed> =
        combine(shields, recentShieldGames, leaguePool, currentTime) { currentShields, games, pool, now ->
            DailyRelevance.build(
                currentShields, games, pool, now, ZoneId.systemDefault(), catalog,
            )
        }

    /** Full browser pool (same dedupe, no limit). */
    val discoverableGames: Flow<List<LeagueGameEntity>> =
        combine(leaguePool, coveredEventIds) { pool, covered ->
            GamesBrowsing.discover(pool, covered, Int.MAX_VALUE)
        }

    /**
     * Games covered by an existing TEAM/GAME shield. Team schedule rows are authoritative;
     * league discovery metadata is optional so a failed scoreboard refresh cannot hide Home's
     * live-game controls.
     */
    val protectedGames: Flow<List<ProtectedLiveGame>> =
        combine(shields, recentProtectedGames, leaguePool) { currentShields, games, pool ->
            ProtectedGames.forHome(
                shields = currentShields,
                games = games,
                leagueGamesById = pool.associateBy { it.eventId },
                leagueIdForTeam = catalog::leagueIdForTeam,
            )
        }

    private val _scoreChecks = MutableStateFlow<Map<String, ScoreCheckState>>(emptyMap())
    val scoreChecks: StateFlow<Map<String, ScoreCheckState>> = _scoreChecks
    private val _gameTimelines = MutableStateFlow<Map<String, GameTimelineState>>(emptyMap())
    val gameTimelines: StateFlow<Map<String, GameTimelineState>> = _gameTimelines

    /** eventId → league game, for looking a GAME shield's league back up (session sheet). */
    val leagueGamesById: Flow<Map<String, LeagueGameEntity>> =
        leaguePool.map { pool -> pool.associateBy { it.eventId } }

    /** Catalog league display names ("nfl" → "NFL") for game rows and filter chips. */
    val leagueNames: Map<String, String> by lazy {
        LEAGUE_SCOREBOARDS.associate { it.leagueId to it.displayName } +
            catalog.leagues.associate { it.id to it.name }
    }

    /** One-shot user-facing notices (snackbar): game-protect results. */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages

    private val _status = MutableStateFlow(ProtectionStatus())
    val status: StateFlow<ProtectionStatus> = _status

    val catalog: TeamCatalog by lazy {
        TeamCatalog.parse(app.assets.open("teams.json").bufferedReader().readText())
    }

    init {
        refreshStatus()
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                currentTime.value = System.currentTimeMillis()
            }
        }
        viewModelScope.launch {
            repo.reconcileEquivalentHiddenAttribution()
            SummaryNotifier.refresh(getApplication())
        }
        viewModelScope.launch {
            repo.vaultDao.pruneRevealedBefore(VaultRetention.cutoff(System.currentTimeMillis()))
            // Spent GAME shields whose revealed rows just aged out have nothing left
            // rendering them — finish the session-scoped cleanup.
            repo.pruneOrphanedGameShields(System.currentTimeMillis())
        }
        // Schedule engine: keep the games cache fresh (12h periodic + refresh on app open).
        // Deliberately triggered from the UI layer, never from the notification interceptor.
        ScheduleRefreshWorker.schedule(app)
        ScheduleRefreshWorker.refreshNow(app)
    }

    fun refreshStatus() {
        val app = getApplication<Application>()
        _status.value = ProtectionStatus(
            listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(app)
                .contains(app.packageName),
            notificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled(),
            serviceConnected = AppPrefs.listenerConnected(app),
        )
    }

    fun addTeamShield(team: Team) = viewModelScope.launch {
        // Deduped by catalogTeamId in the repo — re-onboarding must not fork a duplicate shield.
        repo.addTeamShield(team)
        ScheduleRefreshWorker.refreshNow(getApplication()) // pull the new team's schedule
        WidgetRefresher.refresh(getApplication()) // clears the widget's "Add a shield" state
    }

    fun clearRevealedHistory() = viewModelScope.launch {
        val deleted = repo.clearRevealedHistory()
        _userMessages.emit(
            if (deleted == 0) "No revealed history to clear"
            else "Cleared $deleted revealed notification${if (deleted == 1) "" else "s"}"
        )
    }

    // --- Onboarding live protection demo (v3 amendment item 4) ---

    private val _demoPhase = MutableStateFlow<DemoPhase>(DemoPhase.Idle)
    val demoPhase: StateFlow<DemoPhase> = _demoPhase
    private var demoShieldId: Long? = null
    private var demoVaultRowId: Long? = null
    private var demoIdentity: DemoNotificationIdentity? = null
    private var demoJob: Job? = null

    /**
     * Run the honest live demo against the just-added team: temporarily arm its shield, post a
     * SELF-LABELED fake spoiler on [SpoilerAlertApp.CHANNEL_DEBUG_FAKE] naming the team, and let
     * the REAL interceptor catch it. We watch the vault for the row it hides and surface it as
     * content-free "message hidden". No real notification content is ever involved; the temporary
     * session is undone by [teardownDemo] before the user reaches Home.
     */
    fun runProtectionDemo(teamName: String) {
        val previous = demoJob
        demoJob = viewModelScope.launch {
            // A retry first stops the prior run and removes its fake. This also prevents a
            // cancelled run from resuming after a newer run has started.
            previous?.cancelAndJoin()
            cleanupDemoState()

            val shield = repo.shieldDao.teamShields().let { teams ->
                teams.firstOrNull { it.name == teamName } ?: teams.firstOrNull()
            } ?: return@launch
            val identity = DemoNotificationIdentity(
                shieldId = shield.id,
                sourcePackage = getApplication<Application>().packageName,
                title = DEMO_TITLE,
                text = demoText(teamName),
                postedAfterMillis = System.currentTimeMillis(),
            )
            demoShieldId = shield.id
            demoVaultRowId = null
            demoIdentity = identity
            _demoPhase.value = DemoPhase.Running
            // Temporary 1-hour demo session so the interceptor has an armed shield to match.
            // The onboarding demo must never sweep the user's real notification shade. Its only
            // interception target is the fake notification posted immediately below.
            repo.arm(shield.id, 1, rescanActiveNotifications = false)
            postDemoFake(identity)
            // A real team alert can arrive during this window. Match the exact self-posted fake,
            // never merely the shared shield id, so teardown cannot delete a genuine hide.
            val row = withTimeoutOrNull(DEMO_TIMEOUT_MS) {
                repo.vaultDao.observeHidden()
                    .map { items -> items.firstOrNull(identity::matches) }
                    .first { it != null }
            }
            if (row != null) {
                demoVaultRowId = row.id
                _demoPhase.value = DemoPhase.Hidden(row.id, row.sourceAppLabel)
            } else {
                _demoPhase.value = DemoPhase.Failed
            }
        }
    }

    /** "Reveal" in the demo: read back the (fake) content we hid, proving the loop closes. */
    fun revealProtectionDemo() = viewModelScope.launch {
        val id = demoVaultRowId ?: return@launch
        val row = repo.vaultDao.byId(id) ?: return@launch
        if (demoIdentity?.matches(row) != true) return@launch
        _demoPhase.value = DemoPhase.Revealed(row.title, row.text)
    }

    /** Undo the demo and wait for the running arm/post coroutine to stop before disarming. */
    fun teardownDemo() {
        viewModelScope.launch { stopProtectionDemo() }
    }

    /** Finish is ordered: cleanup completes before the durable onboarding flag can suppress the
     * startup reconciler. The callback only switches the already-live composition to Home. */
    fun finishOnboarding(onFinished: () -> Unit) = viewModelScope.launch {
        stopProtectionDemo()
        AppPrefs.setOnboarded(getApplication())
        onFinished()
    }

    private suspend fun stopProtectionDemo() {
        val running = demoJob
        demoJob = null
        running?.cancelAndJoin()
        cleanupDemoState()
    }

    private suspend fun cleanupDemoState() {
        val sid = demoShieldId
        val identity = demoIdentity
        val immediateRowId = demoVaultRowId?.takeIf { id ->
            val row = repo.vaultDao.byId(id)
            row != null && identity?.matches(row) == true
        }
        val nm = getApplication<Application>().getSystemService(android.app.NotificationManager::class.java)
        nm?.cancel(DEMO_NOTIFICATION_ID) // in case the interceptor never caught it
        // Disarm before waiting for an already-launched interceptor write. This closes the Back
        // race immediately, while still giving the fake row a short chance to finish vaulting so
        // it can be removed instead of surfacing later under a disarmed shield.
        if (sid != null) repo.teardownDemo(sid, immediateRowId)
        if (immediateRowId == null && identity != null) {
            val lateRow = withTimeoutOrNull(DEMO_CLEANUP_TIMEOUT_MS) {
                repo.vaultDao.observeHidden()
                    .map { items -> items.firstOrNull(identity::matches) }
                    .first { it != null }
            }
            lateRow?.let { repo.deleteDemoVaultRow(it.id) }
        }
        demoShieldId = null
        demoVaultRowId = null
        demoIdentity = null
        _demoPhase.value = DemoPhase.Idle
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
    }

    private fun postDemoFake(identity: DemoNotificationIdentity) {
        val ctx = getApplication<Application>()
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(
            DEMO_NOTIFICATION_ID,
            androidx.core.app.NotificationCompat.Builder(ctx, SpoilerAlertApp.CHANNEL_DEBUG_FAKE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(identity.title)
                .setContentText(identity.text)
                .build(),
        )
    }

    fun addCustomShield(name: String, keywords: List<String>) = viewModelScope.launch {
        repo.shieldDao.insert(ShieldEntity(
            name = name,
            aliasesJson = ShieldCodec.encodeAliases(
                keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { Alias(it) }
            ),
            kind = "CUSTOM",
            autoDisarmHours = AppPrefs.autoDisarmHours(getApplication()),
        ))
        WidgetRefresher.refresh(getApplication())
    }

    fun arm(id: Long) = viewModelScope.launch {
        repo.arm(id)
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
    }

    /** Start a time-bounded session. Per-item caught notices are now the default surface. */
    fun armSession(id: Long, hours: Int) = viewModelScope.launch {
        repo.arm(id, hours)
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
    }

    /** "Protect" a matchup from the Big games list / browser: create the session-scoped
     *  GAME shield and arm it for the sheet's chosen duration. */
    fun protectGame(game: LeagueGameEntity, hours: Int) = viewModelScope.launch {
        val id = repo.createGameShield(game)
        if (id == null) {
            _userMessages.tryEmit(CANT_PROTECT_MESSAGE)
            return@launch
        }
        repo.arm(id, hours)
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
    }

    /** "Protect later": create the GAME shield DISARMED — the pregame prompt (enqueued at
     *  creation) offers arming ~10 minutes before kickoff; the Home card works anytime. */
    fun protectGameLater(game: LeagueGameEntity) = viewModelScope.launch {
        val id = repo.createGameShield(game)
        _userMessages.tryEmit(
            when {
                id == null -> CANT_PROTECT_MESSAGE
                AppPrefs.pregamePromptsEnabled(getApplication()) ->
                    "${game.shortName} added — we'll ask about 10 minutes before it starts."
                else -> "${game.shortName} added — start hiding from Home when you're ready."
            }
        )
        WidgetRefresher.refresh(getApplication())
    }

    /**
     * Pull one event from ESPN's public scoreboard and reduce it immediately to a yes/no answer.
     * Scores and leaders are neither persisted nor exposed through this ViewModel.
     */
    fun askScoreQuestion(game: LeagueGameEntity, question: ScoreQuestion) {
        askScoreQuestion(game.eventId, game.leagueId, game.startMillis, question)
    }

    fun askProtectedScoreQuestion(
        game: ProtectedLiveGame,
        question: ScoreQuestion,
        forceRefresh: Boolean = false,
    ) {
        askScoreQuestion(game.eventId, game.leagueId, game.startMillis, question, forceRefresh)
    }

    private fun askScoreQuestion(
        eventId: String,
        leagueId: String,
        startMillis: Long,
        question: ScoreQuestion,
        forceRefresh: Boolean = false,
    ) {
        if (_scoreChecks.value[eventId]?.loading == true) return
        _scoreChecks.value = _scoreChecks.value +
            (eventId to ScoreCheckState(question = question, loading = true))
        viewModelScope.launch {
            val live = try {
                liveScoreFetcher.fetch(
                    leagueId, eventId, startMillis, forceRefresh = forceRefresh,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val answer = live?.let { SpoilerFreeScore.answer(it, question) }
                ?: ScoreAnswer.UNAVAILABLE
            val detail = live?.let { SpoilerFreeScore.detail(it, question) }
            _scoreChecks.value = _scoreChecks.value +
                (eventId to ScoreCheckState(
                    question = question, loading = false, answer = answer, detail = detail,
                    checkedAtMillis = System.currentTimeMillis(),
                    sourceUnavailable = live == null,
                ))
        }
    }

    fun loadGameTimeline(game: ProtectedLiveGame, forceRefresh: Boolean = false) {
        val current = _gameTimelines.value[game.eventId]
        if (current?.loading == true || (!forceRefresh && current?.checkpoints?.isNotEmpty() == true)) return
        updateTimeline(game.eventId) { (it ?: GameTimelineState()).copy(loading = true, sourceUnavailable = false) }
        viewModelScope.launch {
            val checkpoints = try {
                liveScoreFetcher.timelineCheckpoints(game.leagueId, game.eventId, forceRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            updateTimeline(game.eventId) { previous ->
                (previous ?: GameTimelineState()).copy(
                    loading = false,
                    checkpoints = checkpoints,
                    sourceUnavailable = checkpoints.size < 2,
                )
            }
        }
    }

    fun askHistoricalScoreQuestion(
        game: ProtectedLiveGame,
        checkpoint: ScoreCheckpoint,
        question: ScoreQuestion,
    ) {
        val current = _gameTimelines.value[game.eventId] ?: return
        if (current.historicalLoading) return
        updateTimeline(game.eventId) {
            current.copy(
                historicalLoading = true,
                historicalCheckpointId = checkpoint.id,
                historicalAnswer = null,
            )
        }
        viewModelScope.launch {
            val answer = try {
                liveScoreFetcher.answerAt(game.leagueId, game.eventId, checkpoint, question)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ScoreAnswer.UNAVAILABLE
            }
            val checkedAt = System.currentTimeMillis()
            updateTimeline(game.eventId) { previous ->
                (previous ?: current).copy(
                    historicalLoading = false,
                    historicalCheckpointId = checkpoint.id,
                    historicalAnswer = ScoreCheckState(
                        question = question,
                        loading = false,
                        answer = answer,
                        checkedAtMillis = checkedAt,
                        sourceUnavailable = answer == ScoreAnswer.UNAVAILABLE,
                    ),
                )
            }
        }
    }

    fun checkSkipAhead(
        game: ProtectedLiveGame,
        start: ScoreCheckpoint,
        end: ScoreCheckpoint,
        forceRefresh: Boolean = false,
    ) {
        val current = _gameTimelines.value[game.eventId] ?: return
        if (current.skipLoading) return
        updateTimeline(game.eventId) {
            current.copy(
                skipLoading = true,
                skipStartId = start.id,
                skipEndId = end.id,
                skipAnswer = null,
            )
        }
        viewModelScope.launch {
            val answer = try {
                liveScoreFetcher.canSkip(
                    game.leagueId, game.eventId, start, end, forceRefresh,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SkipAnswer.UNAVAILABLE
            }
            val checkedAt = System.currentTimeMillis()
            updateTimeline(game.eventId) { previous ->
                (previous ?: current).copy(
                    skipLoading = false,
                    skipStartId = start.id,
                    skipEndId = end.id,
                    skipAnswer = answer,
                    skipCheckedAtMillis = checkedAt,
                )
            }
        }
    }

    private fun updateTimeline(
        eventId: String,
        transform: (GameTimelineState?) -> GameTimelineState,
    ) {
        _gameTimelines.value = _gameTimelines.value +
            (eventId to transform(_gameTimelines.value[eventId]))
    }

    /** Add exactly one hour to the current selected deadline. */
    fun extend(id: Long) = viewModelScope.launch {
        repo.extend(id)
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
    }

    fun disarm(id: Long) = viewModelScope.launch {
        repo.disarmAndReveal(id)
        SummaryNotifier.refresh(getApplication())
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
    }

    fun endProtection(
        id: Long,
        onComplete: (ShieldRepository.RevealOutcome?) -> Unit = {},
    ) = viewModelScope.launch {
        val outcome = repo.stopAndReveal(id)
        SummaryNotifier.refresh(getApplication())
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
        onComplete(outcome)
    }

    fun deleteShield(id: Long) = viewModelScope.launch {
        repo.disarmAndReveal(id)
        repo.deleteShield(id)
        SummaryNotifier.refresh(getApplication())
        WidgetRefresher.refresh(getApplication())
        SpoilerTileService.requestUpdate(getApplication())
    }

    fun revealItem(id: Long) = viewModelScope.launch {
        repo.revealItem(id)
        SummaryNotifier.refresh(getApplication())
        WidgetRefresher.refresh(getApplication())
    }

    fun setPregamePrompts(id: Long, enabled: Boolean) = viewModelScope.launch {
        repo.shieldDao.setPregamePrompts(id, enabled)
    }

    companion object {
        /** Onboarding demo fake — id in the debug range (50_000+, see [CheckInWorker]). */
        private const val DEMO_NOTIFICATION_ID = 55_555
        private const val DEMO_TIMEOUT_MS = 8_000L
        private const val DEMO_CLEANUP_TIMEOUT_MS = 1_000L
        private const val DEMO_TITLE = "ESPN"
        private fun demoText(teamName: String) = "$teamName win a thriller"
        private const val CANT_PROTECT_MESSAGE =
            "Can't hide this one yet — we don't have team info for this matchup."
    }
}
