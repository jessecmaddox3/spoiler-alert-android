package com.jessemaddox.spoileralert.service

import com.jessemaddox.spoileralert.data.ShieldEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CheckInTest {

    private val now = 1_700_000_000_000L

    /** A shield whose session expires [minutesFromNow] minutes from [now]. */
    private fun shield(armed: Boolean = true, minutesFromNow: Long, hours: Int = 4) = ShieldEntity(
        id = 1L,
        name = "Falcons",
        aliasesJson = "[]",
        kind = "TEAM",
        armed = armed,
        armedAtMillis = now + TimeUnit.MINUTES.toMillis(minutesFromNow) - TimeUnit.HOURS.toMillis(hours.toLong()),
        autoDisarmHours = hours,
    )

    @Test
    fun `null shield - no check-in`() {
        assertFalse(CheckIn.shouldCheckIn(null, now))
    }

    @Test
    fun `disarmed shield - no check-in`() {
        assertFalse(CheckIn.shouldCheckIn(shield(armed = false, minutesFromNow = 10), now))
    }

    @Test
    fun `armed with never-armed timestamp - no check-in`() {
        assertFalse(CheckIn.shouldCheckIn(shield(minutesFromNow = 10).copy(armedAtMillis = null), now))
    }

    @Test
    fun `expiry 10 minutes out - check in`() {
        assertTrue(CheckIn.shouldCheckIn(shield(minutesFromNow = 10), now))
    }

    @Test
    fun `expiry 25 minutes out - extended since scheduling, skip`() {
        assertFalse(CheckIn.shouldCheckIn(shield(minutesFromNow = 25), now))
    }

    @Test
    fun `expiry already past - auto-disarm owns it, skip`() {
        assertFalse(CheckIn.shouldCheckIn(shield(minutesFromNow = -5), now))
    }

    @Test
    fun `expiry exactly now - still eligible`() {
        assertTrue(CheckIn.shouldCheckIn(shield(minutesFromNow = 0), now))
    }
}
