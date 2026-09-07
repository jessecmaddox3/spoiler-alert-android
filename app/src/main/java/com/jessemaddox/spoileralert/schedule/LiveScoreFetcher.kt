package com.jessemaddox.spoileralert.schedule

import kotlin.math.max
import kotlin.math.min

/**
 * Targeted public-scoreboard lookup for one event. The returned scores remain inside the
 * schedule/UI decision path and are never written to Room, logs, notifications, or the vault.
 */
class LiveScoreFetcher(private val fetcher: ScheduleFetcher = ScheduleFetcher()) {
    private val cacheEntries = mutableMapOf<String, CacheEntry>()
    private val timelineCacheEntries = mutableMapOf<String, TimelineCacheEntry>()

    suspend fun fetch(
        leagueId: String,
        eventId: String,
        startMillis: Long,
        forceRefresh: Boolean = false,
    ): ScoreSnapshot? {
        val scoreboard = LEAGUE_SCOREBOARDS.firstOrNull { it.leagueId == leagueId } ?: return null
        val cacheKey = "$leagueId:$eventId"
        val now = System.currentTimeMillis()

        // ESPN's compact golf summary is inconsistent: it can know play has started while omitting
        // the leaderboard fields needed by every other question. Use the proven full scoreboard
        // first, reduce it immediately, and keep only that spoiler-free reduction briefly in memory.
        if (leagueId == "golf") {
            if (!forceRefresh) cached(cacheKey, now)?.let { return it }
            val dates = scoreboardDates(startMillis, now)
            val full = fetcher.fetchGolfScoreboardSnapshot(
                scoreboard.path, eventId, dates, scoreboard.extraQuery,
            )
            val result = full ?: fetcher.fetchScoreSummary(scoreboard.path, leagueId, eventId)
            result?.let { store(cacheKey, it, now) }
            return result
        }

        val summary = fetcher.fetchScoreSummary(scoreboard.path, leagueId, eventId)
        val dates = scoreboardDates(startMillis, now)
        val result = summary ?: fetcher.fetchScoreboard(
                scoreboard.path, leagueId, dates, scoreboard.extraQuery,
                allowTeamless = scoreboard.allowTeamless,
                minimumDurationMillis = scoreboard.minimumDurationMillis,
            ).firstOrNull { it.eventId == eventId }?.let {
                ScoreSnapshot(it.leagueId, it.statusState, it.completed, it.homeScore, it.awayScore)
            }
        return result
    }

    /** Neutral game positions only. Scores and play descriptions stay in this schedule object. */
    suspend fun timelineCheckpoints(
        leagueId: String,
        eventId: String,
        forceRefresh: Boolean = false,
    ): List<ScoreCheckpoint> = timeline(leagueId, eventId, forceRefresh)
        ?.let(SpoilerFreeTimeline::checkpoints).orEmpty()

    suspend fun answerAt(
        leagueId: String,
        eventId: String,
        checkpoint: ScoreCheckpoint,
        question: ScoreQuestion,
    ): ScoreAnswer = timeline(leagueId, eventId)
        ?.let { SpoilerFreeTimeline.answerAt(it, checkpoint, question) }
        ?: ScoreAnswer.UNAVAILABLE

    suspend fun canSkip(
        leagueId: String,
        eventId: String,
        start: ScoreCheckpoint,
        end: ScoreCheckpoint,
        forceRefresh: Boolean = false,
    ): SkipAnswer = timeline(leagueId, eventId, forceRefresh)
        ?.let { SpoilerFreeTimeline.canSkip(it, start, end) }
        ?: SkipAnswer.UNAVAILABLE

    private suspend fun timeline(
        leagueId: String,
        eventId: String,
        forceRefresh: Boolean = false,
    ): ScoreTimeline? {
        if (leagueId == "golf" || leagueId == "tgl" || leagueId == "f1") return null
        val scoreboard = LEAGUE_SCOREBOARDS.firstOrNull { it.leagueId == leagueId } ?: return null
        val cacheKey = "$leagueId:$eventId"
        val now = System.currentTimeMillis()
        if (!forceRefresh) synchronized(timelineCacheEntries) {
            timelineCacheEntries[cacheKey]
                ?.takeIf { now - it.fetchedAtMillis <= TIMELINE_CACHE_MS }
                ?.timeline
        }?.let { return it }
        val result = fetcher.fetchScoreTimeline(scoreboard.path, leagueId, eventId)
        if (result != null) synchronized(timelineCacheEntries) {
            timelineCacheEntries[cacheKey] = TimelineCacheEntry(result, now)
        }
        return result
    }

    private fun scoreboardDates(startMillis: Long, now: Long) = EspnScoreboard.datesParam(
        min(startMillis - ONE_DAY_MS, now - ONE_DAY_MS),
        max(startMillis + ONE_DAY_MS, now + ONE_DAY_MS),
    )

    private fun cached(key: String, now: Long): ScoreSnapshot? = synchronized(cacheEntries) {
        cacheEntries[key]?.takeIf { now - it.fetchedAtMillis <= CACHE_MS }?.snapshot
            .also { if (it == null) cacheEntries.remove(key) }
    }

    private fun store(key: String, snapshot: ScoreSnapshot, now: Long) = synchronized(cacheEntries) {
        cacheEntries[key] = CacheEntry(snapshot, now)
    }

    private data class CacheEntry(val snapshot: ScoreSnapshot, val fetchedAtMillis: Long)
    private data class TimelineCacheEntry(val timeline: ScoreTimeline, val fetchedAtMillis: Long)

    private companion object {
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        const val CACHE_MS = 2L * 60 * 1000
        const val TIMELINE_CACHE_MS = 2L * 60 * 1000
    }
}
