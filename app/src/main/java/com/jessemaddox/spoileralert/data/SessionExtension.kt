package com.jessemaddox.spoileralert.data

import java.util.concurrent.TimeUnit
import kotlin.math.max

data class SessionExtensionPlan(
    val expectedArmedAtMillis: Long,
    val newArmedAtMillis: Long,
    val newAutoDisarmHours: Int,
    val sessionDeadlineMillis: Long,
)

/** Adds exactly one hour to an active deadline. The stored start timestamp also serves as the
 * session's compare-and-swap token, so it advances with the deadline. If the selected deadline
 * has already passed into grace, the new deadline is one full hour from now. */
object SessionExtension {
    val ONE_HOUR_MS: Long = TimeUnit.HOURS.toMillis(1)

    fun plan(shield: ShieldEntity?, nowMillis: Long): SessionExtensionPlan? {
        if (shield == null || !shield.armed) return null
        val armedAt = shield.armedAtMillis ?: return null
        val oldDeadline = ShieldCodec.sessionDeadlineMillis(shield) ?: return null
        val newDeadline = max(oldDeadline, nowMillis) + ONE_HOUR_MS
        val storedHours = shield.autoDisarmHours.coerceIn(1, 72)
        // Advancing armedAt is also the compare-and-swap token change that prevents an old
        // auto-stop worker from clobbering this extension after it has already read the shield.
        val newArmedAt = newDeadline - storedHours * ONE_HOUR_MS
        return SessionExtensionPlan(
            expectedArmedAtMillis = armedAt,
            newArmedAtMillis = newArmedAt,
            newAutoDisarmHours = storedHours,
            sessionDeadlineMillis = newDeadline,
        )
    }
}
