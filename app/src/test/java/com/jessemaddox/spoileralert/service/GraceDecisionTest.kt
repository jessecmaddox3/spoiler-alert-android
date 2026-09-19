package com.jessemaddox.spoileralert.service

import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class GraceDecisionTest {
    private val now = 1_700_000_000_000L

    /** A shield whose final protection deadline, including grace, is [minutesFromNow]. */
    private fun shield(armed: Boolean = true, minutesFromNow: Long, hours: Int = 4) = ShieldEntity(
        id = 1L,
        name = "Falcons",
        aliasesJson = "[]",
        kind = "TEAM",
        armed = armed,
        armedAtMillis = now + TimeUnit.MINUTES.toMillis(minutesFromNow) -
            TimeUnit.HOURS.toMillis(hours.toLong()) - ShieldCodec.GRACE_MS,
        autoDisarmHours = hours,
    )

    @Test fun `null or disarmed shield is a noop`() {
        assertEquals(GraceDecision.Outcome.NOOP, GraceDecision.decide(null, now))
        assertEquals(
            GraceDecision.Outcome.NOOP,
            GraceDecision.decide(shield(armed = false, minutesFromNow = -5), now),
        )
    }

    @Test fun `armed shield without timestamp is a noop`() {
        val s = shield(minutesFromNow = -5).copy(armedAtMillis = null)
        assertEquals(GraceDecision.Outcome.NOOP, GraceDecision.decide(s, now))
    }

    @Test fun `worker running during the grace window is a noop`() {
        assertEquals(GraceDecision.Outcome.NOOP, GraceDecision.decide(shield(minutesFromNow = 10), now))
    }

    @Test fun `worker seals at or after the final grace deadline`() {
        assertEquals(GraceDecision.Outcome.SEAL, GraceDecision.decide(shield(minutesFromNow = 0), now))
        assertEquals(GraceDecision.Outcome.SEAL, GraceDecision.decide(shield(minutesFromNow = -30), now))
    }

    @Test fun `fresh rearm makes a stale worker a noop`() {
        assertEquals(GraceDecision.Outcome.NOOP, GraceDecision.decide(shield(minutesFromNow = 30), now))
    }
}
