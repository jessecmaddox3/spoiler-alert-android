package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.LeagueGameEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GamesBrowsingTest {

    private fun game(id: String, league: String = "nfl", start: Long = 0L) = LeagueGameEntity(
        eventId = id, leagueId = league, name = "n$id", shortName = "s$id",
        startMillis = start, completed = false, homeEspnId = "1", awayEspnId = "2",
        label = null, fetchedAtMillis = 0L,
    )

    @Test
    fun `discover drops games the user's shields already cover and respects the limit`() {
        val pool = listOf(game("a"), game("b"), game("c"), game("d"))
        val out = GamesBrowsing.discover(pool, coveredEventIds = setOf("b"), limit = 2)
        assertEquals(listOf("a", "c"), out.map { it.eventId })
    }

    @Test
    fun `discover with nothing covered returns the pool order unchanged`() {
        val pool = listOf(game("a"), game("b"))
        assertEquals(pool, GamesBrowsing.discover(pool, emptySet(), limit = 10))
    }

    @Test
    fun `groupByDay buckets by local calendar day preserving start order`() {
        val zone = ZoneId.of("America/New_York")
        // 2026-07-18 20:00 ET and 2026-07-19 13:00 ET.
        val sat8pm = Instant.parse("2026-07-19T00:00:00Z").toEpochMilli()
        val sat9pm = Instant.parse("2026-07-19T01:00:00Z").toEpochMilli()
        val sun1pm = Instant.parse("2026-07-19T17:00:00Z").toEpochMilli()
        val sections = GamesBrowsing.groupByDay(
            listOf(game("a", start = sat8pm), game("b", start = sat9pm), game("c", start = sun1pm)),
            zone,
        )
        assertEquals(2, sections.size)
        assertEquals(listOf("a", "b"), sections[0].second.map { it.eventId })
        assertEquals(listOf("c"), sections[1].second.map { it.eventId })
        assertEquals(18, sections[0].first.dayOfMonth)
        assertEquals(19, sections[1].first.dayOfMonth)
    }

    @Test
    fun `happensOn includes an in-progress multi-day event but not tomorrow's game`() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 7, 19)
        val open = game("open", "golf", Instant.parse("2026-07-16T04:00:00Z").toEpochMilli())
            .copy(endMillis = Instant.parse("2026-07-20T04:00:00Z").toEpochMilli())
        val tomorrow = game("tomorrow", start = Instant.parse("2026-07-20T17:00:00Z").toEpochMilli())

        assertEquals(true, GamesBrowsing.happensOn(open, day, zone))
        assertEquals(false, GamesBrowsing.happensOn(tomorrow, day, zone))
    }

    @Test
    fun `empty browser copy distinguishes loading filters today and upcoming`() {
        assertEquals(
            "Events are still loading, or no schedule is available yet. Try again in a moment.",
            GamesBrowsing.emptyStateMessage(false, false, false),
        )
        assertEquals(
            "No events match these filters. Clear the search or choose All sports.",
            GamesBrowsing.emptyStateMessage(true, true, true),
        )
        assertEquals(
            "No events found for today. Try Upcoming to browse ahead.",
            GamesBrowsing.emptyStateMessage(true, false, true),
        )
        assertEquals(
            "No upcoming events found.",
            GamesBrowsing.emptyStateMessage(true, false, false),
        )
    }
}
