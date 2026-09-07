package com.jessemaddox.spoileralert.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EventPresentationTest {
    private val zone = ZoneId.of("America/New_York")

    @Test fun `scheduled event has clock range and relative start`() {
        val start = at(2026, 7, 19, 15, 0)
        val result = EventPresentation.timing(
            start, null, "soccer", at(2026, 7, 19, 13, 32), zone,
        )

        assertEquals("3:00 PM–5:30 PM", result.schedule)
        assertEquals("Starts in 1h 28m", result.relative)
    }

    @Test fun `live event says how long ago it started`() {
        val start = at(2026, 7, 19, 15, 0)
        val result = EventPresentation.timing(
            start, null, "soccer", at(2026, 7, 19, 15, 22), zone,
        )

        assertEquals("Started 22m ago", result.relative)
    }

    @Test fun `multi-day event uses dates instead of misleading all-day clock range`() {
        val result = EventPresentation.timing(
            at(2026, 7, 16, 0, 0), at(2026, 7, 20, 0, 0), "golf",
            at(2026, 7, 19, 8, 0), zone,
        )

        assertEquals("Jul 16–Jul 20", result.schedule)
        assertEquals("Happening now", result.relative)
    }

    @Test fun `league presentation distinguishes core sports`() {
        assertEquals(SportPresentation("Soccer", "⚽"), EventPresentation.sport("mls"))
        assertEquals(SportPresentation("Golf", "⛳"), EventPresentation.sport("golf"))
        assertEquals(SportPresentation("Football", "🏈"), EventPresentation.sport("nfl"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
