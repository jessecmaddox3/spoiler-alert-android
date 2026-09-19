package com.jessemaddox.spoileralert.service

import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldEntity

/**
 * Fire-time eligibility for the "still avoiding spoilers?" check-in that lands ~15 minutes
 * before a protection session enters its final grace hour. Pure logic, no Android deps —
 * [CheckInWorker]
 * gathers state and this decides.
 *
 * Eligible only when the shield is still armed AND its expiry falls inside
 * [now, now + WINDOW_MS]. An expiry further out means the user re-armed/extended after this
 * check-in was scheduled (a fresh one is queued); an expiry in the past belongs to
 * [AutoDisarmWorker].
 */
object CheckIn {
    /** Check in this long before the selected session window ends. */
    const val LEAD_TIME_MS = 15L * 60 * 1000

    /** Fire-time slop: expiry must be within this far in the future to still be "ours". */
    const val WINDOW_MS = 20L * 60 * 1000

    fun shouldCheckIn(shield: ShieldEntity?, nowMillis: Long): Boolean {
        if (shield == null || !shield.armed) return false
        val expiresAt = ShieldCodec.sessionDeadlineMillis(shield) ?: return false
        return expiresAt >= nowMillis && expiresAt <= nowMillis + WINDOW_MS
    }
}
