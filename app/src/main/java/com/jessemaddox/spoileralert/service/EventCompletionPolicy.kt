package com.jessemaddox.spoileralert.service

object EventCompletionPolicy {
    const val RECHECK_MS = 20L * 60 * 1000

    /** Null means stop checking. A confirmed final never schedules another fetch. */
    fun nextDelayMillis(completed: Boolean?, nowMillis: Long, deadlineMillis: Long): Long? =
        if (completed == true || nowMillis + RECHECK_MS >= deadlineMillis) null else RECHECK_MS
}
