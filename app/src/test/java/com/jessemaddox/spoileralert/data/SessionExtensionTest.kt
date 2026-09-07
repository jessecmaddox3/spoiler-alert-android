package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionExtensionTest {
    private val hour = SessionExtension.ONE_HOUR_MS

    @Test fun `active session deadline gains exactly one hour`() {
        val shield = ShieldEntity(
            id = 1, name = "The Open", aliasesJson = "[]", kind = "GAME",
            armed = true, armedAtMillis = 1_000L, autoDisarmHours = 4,
        )

        val plan = SessionExtension.plan(shield, nowMillis = 1_000L + 3 * hour)!!

        assertEquals(1_000L + hour, plan.newArmedAtMillis)
        assertEquals(4, plan.newAutoDisarmHours)
        assertEquals(1_000L + 5 * hour, plan.sessionDeadlineMillis)
    }

    @Test fun `extension during grace provides one full hour from now`() {
        val oldArmedAt = 1_000L
        val shield = ShieldEntity(
            id = 1, name = "The Open", aliasesJson = "[]", kind = "GAME",
            armed = true, armedAtMillis = oldArmedAt, autoDisarmHours = 4,
        )
        val now = oldArmedAt + 4 * hour + 5 * 60_000L

        val plan = SessionExtension.plan(shield, now)!!

        assertEquals(now + hour, plan.sessionDeadlineMillis)
        assertEquals(plan.sessionDeadlineMillis, plan.newArmedAtMillis + plan.newAutoDisarmHours * hour)
    }

    @Test fun `unarmed session cannot be extended`() {
        val shield = ShieldEntity(
            id = 1, name = "The Open", aliasesJson = "[]", kind = "GAME",
            armed = false, armedAtMillis = null, autoDisarmHours = 4,
        )
        assertNull(SessionExtension.plan(shield, nowMillis = 1_000L))
    }
}
