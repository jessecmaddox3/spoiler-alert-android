package com.jessemaddox.spoileralert.schedule

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jessemaddox.spoileralert.data.AppDatabase
import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.widget.SpoilerTileService
import com.jessemaddox.spoileralert.widget.WidgetRefresher
import java.util.concurrent.TimeUnit

/**
 * Refreshes cached game schedules for every TEAM shield whose catalog team has an ESPN id.
 * Deliberately isolated from the notification pipeline: reads shields (names/catalog ids only),
 * writes games. Never touches the vault, never triggered by the interceptor.
 */
class ScheduleRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val catalog = runCatching {
            TeamCatalog.parse(applicationContext.assets.open("teams.json").bufferedReader().readText())
        }.getOrElse { e ->
            Log.w(TAG, "Failed to load team catalog: $e")
            return Result.failure()
        }
        val espnByCatalogId = catalog.leagues.flatMap { it.teams }
            .mapNotNull { t -> t.espn?.let { t.id to it } }.toMap()

        val fetcher = ScheduleFetcher()
        val now = System.currentTimeMillis()
        val recentCutoff = now - RECENT_WINDOW_MS

        // Populate Home-critical event feeds first. In particular, a live multi-day golf major
        // should not wait behind every saved-team schedule and long-tail league request.
        refreshLeagueGames(
            db, fetcher, now,
            HOME_PRIORITY_LEAGUE_IDS.mapNotNull { priorityId ->
                LEAGUE_SCOREBOARDS.firstOrNull { it.leagueId == priorityId }
            },
            prune = false,
        )

        for (shield in db.shieldDao().teamShields()) {
            val espnPath = shield.catalogTeamId?.let { espnByCatalogId[it] } ?: continue
            val games = fetcher.fetch(espnPath)
            if (games.isEmpty()) continue // fetch failed or nothing scheduled; keep the old cache
            val rows = games
                .filter { !it.completed || it.startMillis >= recentCutoff } // upcoming + recent
                .map {
                    GameEntity(
                        id = it.eventId, shieldId = shield.id, name = it.name,
                        shortName = it.shortName, startMillis = it.startMillis,
                        completed = it.completed, fetchedAtMillis = now,
                    )
                }
            db.gameDao().upsertAll(rows)
            Log.i(TAG, "Refreshed ${rows.size} games for shield '${shield.name}'")
            schedulePregamePrompts(rows)
        }
        db.gameDao().deleteCompletedBefore(recentCutoff)
        refreshLeagueGames(
            db, fetcher, now,
            LEAGUE_SCOREBOARDS.filterNot { it.leagueId in HOME_PRIORITY_LEAGUE_IDS },
            prune = true,
        )
        refreshGameShields(db)
        WidgetRefresher.refreshNow(applicationContext) // fresh next-game line on any placed widget
        SpoilerTileService.requestUpdate(applicationContext) // idle tile's next-game subtitle too
        return Result.success(workDataOf(PARTIAL_REFRESH to fetcher.hadFailure))
    }

    /**
     * Re-derive every GAME shield's name/aliases/cached game row from the just-refreshed
     * league_games pool (collated review finding 2, call site (a)) — the periodic/on-open
     * refresh is the main channel a schedule correction reaches an already-protected or
     * "Protect later" matchup through. See [ShieldRepository.refreshGameShield].
     */
    private suspend fun refreshGameShields(db: AppDatabase) {
        val repo = ShieldRepository(applicationContext, db)
        for (shield in db.shieldDao().gameShields()) {
            repo.refreshGameShield(shield.id)
        }
    }

    /**
     * Refresh the league-wide discovery pool (`league_games`, v3 amendment item 2) for ALL
     * ESPN-capable feeds — not just leagues the user has shields in, because Find an event
     * must surface primetime games, major events, and the tucked-away long tail regardless of
     * saved teams. One bounded GET per feed spans the last seven days through eight days ahead.
     * A failed/empty fetch keeps that league's old cache. Recently completed events remain
     * available for recordings; pruning removes only events outside the recording window.
     */
    private suspend fun refreshLeagueGames(
        db: AppDatabase,
        fetcher: ScheduleFetcher,
        now: Long,
        scoreboards: List<LeagueScoreboard>,
        prune: Boolean,
    ) {
        val dates = EspnScoreboard.datesParam(now - RECENT_WINDOW_MS, now + LEAGUE_WINDOW_MS)
        for (scoreboard in scoreboards) {
            val games = fetcher.fetchScoreboard(
                scoreboard.path, scoreboard.leagueId, dates, scoreboard.extraQuery,
                allowTeamless = scoreboard.allowTeamless,
                minimumDurationMillis = scoreboard.minimumDurationMillis,
            )
            if (games.isEmpty()) continue // off-season, fetch failure, or empty window
            db.leagueGameDao().upsertAll(games.map {
                LeagueGameEntity(
                    eventId = it.eventId, leagueId = it.leagueId, name = it.name,
                    shortName = it.shortName, startMillis = it.startMillis,
                    completed = it.completed, homeEspnId = it.homeEspnId,
                    awayEspnId = it.awayEspnId, label = it.label, fetchedAtMillis = now,
                    endMillis = it.endMillis, phase = it.phase,
                )
            })
            Log.i(TAG, "Refreshed ${games.size} events for '${scoreboard.leagueId}'")
        }
        if (prune) db.leagueGameDao().pruneBefore(now - RECENT_WINDOW_MS)
    }

    /**
     * Enqueue a [PregamePromptWorker] 10 minutes before each upcoming game. REPLACE, not
     * KEEP: the same event id survives postponements/reschedules with a new startMillis, so
     * a KEEP'd enqueue would pin the delay computed from the *old* start forever. REPLACE
     * lets every refresh recompute the delay from the latest startMillis, in both directions
     * (earlier or later). Eligibility (toggles, armed state, game still on, and now also an
     * upper bound on how early the fire is relative to the re-read start) is re-checked at
     * fire time in [PregamePrompt.shouldPrompt], so a fire that predates a later reschedule
     * is a harmless no-op skip — which is also why disarm/delete never needs to cancel these
     * proactively.
     *
     * Games already inside (or past) the lead window at refresh time are still enqueued with
     * a clamped initial delay of 0 rather than skipped outright, so a shield added/refreshed
     * within 10 minutes of kickoff still gets a prompt; shouldPrompt's fire-time re-check
     * remains the single source of truth for whether it's actually still eligible to show.
     */
    private fun schedulePregamePrompts(games: List<GameEntity>) {
        for (game in games) {
            PregamePromptWorker.enqueue(
                applicationContext, game.id, game.shieldId, game.startMillis, game.completed,
            )
        }
    }

    companion object {
        private const val TAG = "ScheduleRefresh"
        private const val RECENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        /** Discovery-pool horizon: rolling today → +8 days. */
        private const val LEAGUE_WINDOW_MS = 8L * 24 * 60 * 60 * 1000
        const val PARTIAL_REFRESH = "partial_refresh"

        private const val PERIODIC_WORK = "schedule-refresh"
        private const val ONE_TIME_WORK = "schedule-refresh-now"

        private val networkRequired =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Enqueue the 12h periodic refresh (idempotent — KEEP). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScheduleRefreshWorker>(12, TimeUnit.HOURS)
                    .setConstraints(networkRequired)
                    .build(),
            )
        }

        /** Refresh immediately (app open, shield added). */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScheduleRefreshWorker>()
                    .setConstraints(networkRequired)
                    .build(),
            )
        }
    }
}
