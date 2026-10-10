package com.jessemaddox.spoileralert.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class SessionDurationsTest {

    // --- hoursUntilTonight: whole hours from now until the next 23:00 local, min 1 ---

    @Test
    fun `morning rounds up to whole hours until 11pm`() {
        // 9:14 → 13h46m → 14
        assertEquals(14, SessionDurations.hoursUntilTonight(LocalDateTime.of(2026, 7, 17, 9, 14)))
    }

    @Test
    fun `exact hour boundary is not rounded`() {
        // 9:00 → exactly 14h
        assertEquals(14, SessionDurations.hoursUntilTonight(LocalDateTime.of(2026, 7, 17, 9, 0)))
    }

    @Test
    fun `just before 11pm clamps to minimum 1`() {
        // 22:30 → 30m → ceil = 1
        assertEquals(1, SessionDurations.hoursUntilTonight(LocalDateTime.of(2026, 7, 17, 22, 30)))
        // 22:59 → 1m → 1
        assertEquals(1, SessionDurations.hoursUntilTonight(LocalDateTime.of(2026, 7, 17, 22, 59)))
    }

    @Test
    fun `at or after 11pm targets tomorrow night`() {
        // 23:00 exactly → tomorrow 23:00 → 24
        assertEquals(24, SessionDurations.hoursUntilTonight(LocalDateTime.of(2026, 7, 17, 23, 0)))
        // 23:30 → 23h30m → 24
        assertEquals(24, SessionDurations.hoursUntilTonight(LocalDateTime.of(2026, 7, 17, 23, 30)))
        // 0:30 → 22h30m → 23
        assertEquals(23, SessionDurations.hoursUntilTonight(LocalDateTime.of(2026, 7, 18, 0, 30)))
    }

    // --- hoursUntil: whole hours to a picked end time, ceil, min 1 cap 72 ---

    @Test
    fun `hoursUntil rounds up and clamps to minimum 1`() {
        val now = LocalDateTime.of(2026, 7, 17, 20, 0)
        assertEquals(3, SessionDurations.hoursUntil(now, LocalDateTime.of(2026, 7, 17, 23, 0)))
        assertEquals(4, SessionDurations.hoursUntil(now, LocalDateTime.of(2026, 7, 17, 23, 30)))
        assertEquals(1, SessionDurations.hoursUntil(now, LocalDateTime.of(2026, 7, 17, 20, 10)))
        assertEquals(1, SessionDurations.hoursUntil(now, now)) // past/now still min 1
    }

    @Test
    fun `hoursUntil caps at 72`() {
        val now = LocalDateTime.of(2026, 7, 17, 20, 0)
        assertEquals(72, SessionDurations.hoursUntil(now, now.plusDays(10)))
    }

    // --- suggestedHours: sport-aware default session length by catalog league id ---

    @Test
    fun `football suggests 5 hours`() {
        assertEquals(5, SessionDurations.suggestedHours("nfl"))
        assertEquals(5, SessionDurations.suggestedHours("cfb"))
    }

    @Test
    fun `baseball suggests 4 hours`() {
        assertEquals(4, SessionDurations.suggestedHours("mlb"))
    }

    @Test
    fun `basketball and soccer suggest 3 hours`() {
        assertEquals(3, SessionDurations.suggestedHours("nba"))
        assertEquals(3, SessionDurations.suggestedHours("epl"))
        assertEquals(3, SessionDurations.suggestedHours("mls"))
        assertEquals(3, SessionDurations.suggestedHours("soccer"))
    }

    @Test
    fun `unknown or missing league falls back to the 4-hour default`() {
        assertEquals(4, SessionDurations.suggestedHours("f1"))
        assertEquals(4, SessionDurations.suggestedHours("golf"))
        assertEquals(4, SessionDurations.suggestedHours(null))
    }

    // --- formatRemaining: live countdown text for chips ("2h 40m", "12m", "<1m") ---

    @Test
    fun `formats hours and minutes`() {
        assertEquals("2h 40m", SessionDurations.formatRemaining(2 * 3_600_000L + 40 * 60_000L))
    }

    @Test
    fun `formats exact hours without minutes`() {
        assertEquals("4h", SessionDurations.formatRemaining(4 * 3_600_000L))
    }

    @Test
    fun `formats sub-hour as minutes only`() {
        assertEquals("12m", SessionDurations.formatRemaining(12 * 60_000L))
    }

    @Test
    fun `formats under a minute and negative as under-1m`() {
        assertEquals("<1m", SessionDurations.formatRemaining(30_000L))
        assertEquals("<1m", SessionDurations.formatRemaining(-5_000L))
    }
}
