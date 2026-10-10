package com.jessemaddox.spoileralert.data

import com.jessemaddox.spoileralert.data.GameTimeProtection.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTimeProtectionTest {
    private val min = 60_000L
    private val hr = 60 * min
    private val kickoff = 1_900_000_000_000L

    private fun shield(
        kind: String = "TEAM",
        autoProtect: Boolean = true,
        stoppedAt: Long? = null,
    ) = ShieldEntity(id = 7, name = "Cedar Comets", aliasesJson = "[]", kind = kind,
        autoProtect = autoProtect, gameTimeStoppedAtMillis = stoppedAt)

    private val football = GameTimeProtection.window(7, "fiction-event", kickoff, "cfb")

    @Test fun `window opens shortly before kickoff and closes at the expected finish`() {
        assertEquals(kickoff - 10 * min, football.startMillis)
        // 3.5h game + 45m post-game buffer
        assertEquals(kickoff + 4 * hr + 15 * min, football.endMillis)
    }

    @Test fun `a later public end keeps a multi-day event covered`() {
        val golf = GameTimeProtection.window(7, "fiction-open", kickoff, "golf", kickoff + 80 * hr)
        assertEquals(kickoff + 80 * hr, golf.endMillis)
    }

    @Test fun `window is open from its start up to but not including its end`() {
        assertFalse(football.isOpen(football.startMillis - 1))
        assertTrue(football.isOpen(football.startMillis))
        assertTrue(football.isOpen(football.endMillis - 1))
        assertFalse(football.isOpen(football.endMillis))
    }

    @Test fun `opting in hours early does not start hiding before the window`() {
        val morning = kickoff - 7 * hr - 50 * min
        assertEquals(Start.Skip("game window has not opened"),
            GameTimeProtection.decide(shield(), null, football, morning))
    }

    @Test fun `a start worker waking a moment early still starts`() {
        assertEquals(Start.Arm(5), GameTimeProtection.decide(shield(), null, football, football.startMillis - min))
    }

    @Test fun `an open window starts a session that reaches the expected finish`() {
        // 10m lead + 4h15m coverage = 4h25m → 5 whole hours
        assertEquals(Start.Arm(5), GameTimeProtection.decide(shield(), null, football, football.startMillis))
    }

    @Test fun `a window opened mid-game covers only what is left`() {
        assertEquals(Start.Arm(2), GameTimeProtection.decide(shield(), null, football, kickoff + 3 * hr)) // 1h15m → 2
    }

    @Test fun `a game days away is covered exactly like one tonight`() {
        val farGame = GameTimeProtection.window(7, "fiction-far", kickoff + 5 * 24 * hr, "nfl")
        assertEquals(Start.Arm(5), GameTimeProtection.decide(shield(), null, farGame, farGame.startMillis))
    }

    @Test fun `a shield that has not opted in never starts on its own`() {
        assertEquals(Start.Skip("game-time protection is off"),
            GameTimeProtection.decide(shield(autoProtect = false), null, football, kickoff))
    }

    @Test fun `a stop during this game keeps it stopped`() {
        assertEquals(Start.Skip("stopped during this game"),
            GameTimeProtection.decide(shield(stoppedAt = kickoff + hr), null, football, kickoff + 2 * hr))
    }

    @Test fun `stopping an early start before the window does not cancel the game`() {
        val stoppedAfternoon = kickoff - 4 * hr
        assertEquals(Start.Arm(5),
            GameTimeProtection.decide(shield(stoppedAt = stoppedAfternoon), null, football, football.startMillis))
    }

    @Test fun `a stop during an earlier game does not block a later one`() {
        val yesterday = kickoff - 24 * hr
        assertEquals(Start.Arm(5),
            GameTimeProtection.decide(shield(stoppedAt = yesterday), null, football, football.startMillis))
    }

    @Test fun `a running session that already reaches the finish is left alone`() {
        assertEquals(Start.AlreadyCovered, GameTimeProtection.decide(shield(), football.endMillis, football, kickoff))
    }

    @Test fun `a running session that ends before the finish is replaced by one that reaches it`() {
        assertEquals(Start.Arm(5), GameTimeProtection.decide(shield(), kickoff + hr, football, football.startMillis))
    }

    @Test fun `a multi-day event restarts when its capped session lapses mid-event`() {
        val golf = GameTimeProtection.window(7, "fiction-open", kickoff, "golf", kickoff + 84 * hr)
        val lapsed = golf.startMillis + 73 * hr
        assertEquals(Start.Arm(12), GameTimeProtection.decide(shield(), null, golf, lapsed)) // 11h10m left → 12
    }

    @Test fun `an explicit request starts now and still reaches the finish`() {
        val morning = kickoff - 7 * hr - 50 * min
        // 7h50m wait + 4h15m coverage = 12h05m → 13
        assertEquals(Start.Arm(13),
            GameTimeProtection.decide(shield(autoProtect = false), null, football, morning, explicit = true))
    }

    @Test fun `an explicit request overrides an earlier stop`() {
        assertEquals(Start.Arm(5), GameTimeProtection.decide(
            shield(stoppedAt = kickoff), null, football, football.startMillis + 20 * min, explicit = true))
    }

    @Test fun `nothing starts once the window is over or the game left the cache`() {
        assertEquals(Start.Skip("game window is over"),
            GameTimeProtection.decide(shield(), null, football, football.endMillis, explicit = true))
        assertEquals(Start.Skip("game no longer in schedule cache"),
            GameTimeProtection.decide(shield(), null, null, kickoff))
    }

    @Test fun `custom keyword shields have no game windows`() {
        assertEquals(Start.Skip("shield kind has no schedule"),
            GameTimeProtection.decide(shield(kind = "CUSTOM"), null, football, kickoff))
    }

    @Test fun `due windows are open, opted in, not stopped, and not already protected`() {
        val other = GameTimeProtection.window(8, "fiction-other", kickoff, "nba")
        val optedIn = mapOf<Long, Long?>(7L to null, 8L to null)
        assertEquals(listOf(football, other),
            GameTimeProtection.dueWindows(listOf(football, other), optedIn, emptySet(), kickoff))
        // Already protected by a running session
        assertEquals(listOf(other),
            GameTimeProtection.dueWindows(listOf(football, other), optedIn, setOf(7L), kickoff))
        // Stopped during this game
        assertEquals(listOf(other), GameTimeProtection.dueWindows(
            listOf(football, other), mapOf(7L to kickoff, 8L to null), emptySet(), kickoff + min))
        // Opted out since the windows were cached
        assertEquals(listOf(other),
            GameTimeProtection.dueWindows(listOf(football, other), mapOf(8L to null), emptySet(), kickoff))
        // Not open yet
        assertEquals(emptyList<GameTimeProtection.Window>(), GameTimeProtection.dueWindows(
            listOf(football, other), optedIn, emptySet(), football.startMillis - 1))
    }

    @Test fun `a stop during overlapping doubleheader windows keeps both stopped`() {
        val first = GameTimeProtection.window(7, "fiction-first", kickoff - 3 * hr, "mlb")
        val second = GameTimeProtection.window(7, "fiction-second", kickoff, "mlb")
        val stoppedAt = kickoff + 10 * min // both windows open
        assertEquals(emptyList<GameTimeProtection.Window>(), GameTimeProtection.dueWindows(
            listOf(first, second), mapOf(7L to stoppedAt), emptySet(), kickoff + 20 * min))
    }

    @Test fun `hours to reach round up and stay within the session cap`() {
        assertEquals(1, GameTimeProtection.hoursToReach(kickoff + min, kickoff))
        assertEquals(2, GameTimeProtection.hoursToReach(kickoff + hr + 1, kickoff))
        assertEquals(72, GameTimeProtection.hoursToReach(kickoff + 100 * hr, kickoff))
    }
}
