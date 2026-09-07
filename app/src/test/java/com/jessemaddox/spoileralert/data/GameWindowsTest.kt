package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Windowing helper behind the ship-blocker fix (collated review finding 1): one shared
 *  look-back constant/cutoff for every "is this game still current" surface. */
class GameWindowsTest {

    @Test
    fun `look-back window is four hours`() {
        assertEquals(4L * 60 * 60 * 1000, GameWindows.IN_PROGRESS_LOOKBACK_MS)
    }

    @Test
    fun `sinceMillis subtracts the look-back from now`() {
        val now = 10_000_000_000L
        assertEquals(now - GameWindows.IN_PROGRESS_LOOKBACK_MS, GameWindows.sinceMillis(now))
    }

    @Test
    fun `hasStarted is true for a kickoff at or before now`() {
        val now = 10_000_000L
        assertTrue(GameWindows.hasStarted(now, now))
        assertTrue(GameWindows.hasStarted(now - 1, now))
    }

    @Test
    fun `hasStarted is false for a kickoff after now`() {
        val now = 10_000_000L
        assertFalse(GameWindows.hasStarted(now + 1, now))
    }
}
