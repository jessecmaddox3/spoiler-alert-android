package com.jessemaddox.spoileralert.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveScoreFetcherTest {
    @Test fun `golf uses full scoreboard first and caches only its reduced snapshot`() = runBlocking {
        val expected = completeGolfSnapshot()
        val source = FakeScheduleFetcher(golf = expected, summary = completeGolfSnapshot())
        val fetcher = LiveScoreFetcher(source)

        val first = fetcher.fetch("golf", "open", 1_768_000_000_000L)
        val second = fetcher.fetch("golf", "open", 1_768_000_000_000L)

        assertSame(expected, first)
        assertSame(expected, second)
        assertEquals(1, source.golfCalls)
        assertEquals(0, source.summaryCalls)
    }

    @Test fun `forced golf refresh bypasses the short lived cache`() = runBlocking {
        val expected = completeGolfSnapshot()
        val source = FakeScheduleFetcher(golf = expected, summary = null)
        val fetcher = LiveScoreFetcher(source)

        fetcher.fetch("golf", "open", 1_768_000_000_000L)
        fetcher.fetch("golf", "open", 1_768_000_000_000L, forceRefresh = true)

        assertEquals(2, source.golfCalls)
    }

    @Test fun `golf falls back to compact summary only when full scoreboard fails`() = runBlocking {
        val expected = completeGolfSnapshot()
        val source = FakeScheduleFetcher(golf = null, summary = expected)

        assertSame(expected, LiveScoreFetcher(source).fetch("golf", "open", 1_768_000_000_000L))
        assertEquals(1, source.golfCalls)
        assertEquals(1, source.summaryCalls)
    }

    @Test fun `team game snapshots with exact scores are never cached`() = runBlocking {
        val teamSnapshot = ScoreSnapshot(
            leagueId = "nfl", statusState = "in", completed = false,
            homeScore = 7, awayScore = 3,
        )
        val source = FakeScheduleFetcher(golf = null, summary = teamSnapshot)
        val fetcher = LiveScoreFetcher(source)

        fetcher.fetch("nfl", "game", 1_768_000_000_000L)
        fetcher.fetch("nfl", "game", 1_768_000_000_000L)

        assertEquals(2, source.summaryCalls)
    }

    private fun completeGolfSnapshot() = ScoreSnapshot(
        leagueId = "golf", statusState = "in", completed = false,
        homeScore = null, awayScore = null, currentRound = 4,
        leadersStarted = true, leaderHolesCompleted = 7,
        tiedForLead = false, contenderWithinTwo = true,
        playoff = false, weatherDelay = false,
    )

    private class FakeScheduleFetcher(
        private val golf: ScoreSnapshot?,
        private val summary: ScoreSnapshot?,
    ) : ScheduleFetcher() {
        var golfCalls = 0
        var summaryCalls = 0

        override suspend fun fetchGolfScoreboardSnapshot(
            scoreboardPath: String,
            eventId: String,
            dates: String,
            extraQuery: String?,
        ): ScoreSnapshot? {
            golfCalls++
            return golf
        }

        override suspend fun fetchScoreSummary(
            scoreboardPath: String,
            leagueId: String,
            eventId: String,
        ): ScoreSnapshot? {
            summaryCalls++
            return summary
        }
    }
}
