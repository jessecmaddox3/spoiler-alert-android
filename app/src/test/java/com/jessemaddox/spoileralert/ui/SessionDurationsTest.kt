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

    // --- gameAwareDefaultHours: default that covers the actual game (start + length + buffer) ---

    private val now = 1_700_000_000_000L // fixed reference "now" for game-aware math
    private fun min(m: Long) = m * 60_000L
    private fun hr(h: Long) = h * 3_600_000L

    @Test
    fun `no known game falls back to the flat sport-aware suggestion`() {
        assertEquals(3, SessionDurations.gameAwareDefaultHours("nba", null, now))
        assertEquals(5, SessionDurations.gameAwareDefaultHours("nfl", null, now))
        assertEquals(4, SessionDurations.gameAwareDefaultHours(null, null, now))
    }

    @Test
    fun `football kicking off now covers game plus post-game buffer`() {
        // 3.5h game + 45m buffer = 4h15m → ceil 5
        assertEquals(5, SessionDurations.gameAwareDefaultHours("nfl", now, now))
        assertEquals(5, SessionDurations.gameAwareDefaultHours("mlb", now, now)) // baseball also 3.5h
    }

    @Test
    fun `basketball kicking off now covers 2_5h plus buffer`() {
        // 2.5h + 45m = 3h15m → ceil 4
        assertEquals(4, SessionDurations.gameAwareDefaultHours("nba", now, now))
    }

    @Test
    fun `soccer leagues use their own lengths`() {
        // epl 2h + 45m = 2h45m → ceil 3
        assertEquals(3, SessionDurations.gameAwareDefaultHours("epl", now, now))
        assertEquals(3, SessionDurations.gameAwareDefaultHours("mls", now, now))
        // knockout soccer / World Cup 2.5h + 45m = 3h15m → ceil 4
        assertEquals(4, SessionDurations.gameAwareDefaultHours("soccer", now, now))
        assertEquals(4, SessionDurations.gameAwareDefaultHours("fifa.world", now, now))
    }

    @Test
    fun `unknown league uses a 3h default game length`() {
        // 3h + 45m = 3h45m → ceil 4
        assertEquals(4, SessionDurations.gameAwareDefaultHours("golf", now, now))
    }

    @Test
    fun `imminent game folds the pre-kickoff wait into the default`() {
        // nba starting in 30m: 0.5h wait + 3.25h coverage = 3.75h → ceil 4
        assertEquals(4, SessionDurations.gameAwareDefaultHours("nba", now + min(30), now))
    }

    @Test
    fun `in-progress game covers only the remaining time`() {
        // nfl started 1h ago: end = now + 3.25h → ceil 4
        assertEquals(4, SessionDurations.gameAwareDefaultHours("nfl", now - hr(1), now))
    }

    @Test
    fun `nearly-over game clamps to the 1-hour minimum`() {
        // nba: length+buffer = 3.25h; start 3h05m ago → ends in ~10m → ceil 1
        assertEquals(1, SessionDurations.gameAwareDefaultHours("nba", now - min(185), now))
    }

    @Test
    fun `a game already over falls back to the flat suggestion`() {
        // nba ended well before now → flat 3, not a tiny game-aware number
        assertEquals(3, SessionDurations.gameAwareDefaultHours("nba", now - hr(6), now))
    }

    @Test
    fun `a game too far in the future falls back to the flat suggestion`() {
        // starts 8h out (beyond the near window) → flat 3, not an 11h session
        assertEquals(3, SessionDurations.gameAwareDefaultHours("nba", now + hr(8), now))
    }

    @Test
    fun `coverableGameEndMillis mirrors the default decision`() {
        // known + imminent → non-null end at start + length + buffer
        assertEquals(now + hr(2) + min(30) + min(45),
            SessionDurations.coverableGameEndMillis("nba", now, now))
        // null / over / far-future → null (caller uses the flat suggestion)
        assertEquals(null, SessionDurations.coverableGameEndMillis("nba", null, now))
        assertEquals(null, SessionDurations.coverableGameEndMillis("nba", now - hr(6), now))
        assertEquals(null, SessionDurations.coverableGameEndMillis("nba", now + hr(8), now))
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
