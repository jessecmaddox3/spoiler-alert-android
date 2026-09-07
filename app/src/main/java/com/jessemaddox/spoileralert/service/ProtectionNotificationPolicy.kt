package com.jessemaddox.spoileralert.service

object ProtectionNotificationPolicy {
    private const val EXTEND_WINDOW_MS = 30 * 60_000L
    private const val SUMMARY_ID_BASE = 1_000
    private const val SUMMARY_ID_SPAN = 8_000

    fun summaryId(shieldId: Long): Int = SUMMARY_ID_BASE + (shieldId % SUMMARY_ID_SPAN).toInt()

    fun isSummaryId(id: Int): Boolean = id in SUMMARY_ID_BASE until (SUMMARY_ID_BASE + SUMMARY_ID_SPAN)

    fun groupKey(shieldId: Long): String = "spoiler-alert-shield-$shieldId"

    fun showExtend(deadlineMillis: Long?, nowMillis: Long): Boolean {
        val remaining = deadlineMillis?.minus(nowMillis) ?: return false
        return remaining in 1..EXTEND_WINDOW_MS
    }

    fun interceptedCopy(count: Int): String = when (count) {
        0 -> "No notifications hidden yet"
        1 -> "1 notification hidden"
        else -> "$count notifications hidden"
    }
}
