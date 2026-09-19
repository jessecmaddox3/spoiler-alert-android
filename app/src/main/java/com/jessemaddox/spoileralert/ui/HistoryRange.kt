package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.data.VaultRetention

enum class HistoryRange(val label: String, val days: Int, val emptyCopy: String) {
    WEEK("This week", 7, "No notifications revealed this week."),
    MONTH("30 days", 30, "No notifications revealed in the last 30 days."),
    YEAR("1 year", VaultRetention.DAYS, "No notifications revealed in the last year."),
    ;

    fun cutoff(nowMillis: Long): Long = nowMillis - days * DAY_MILLIS

    fun includes(item: VaultEntity, nowMillis: Long): Boolean =
        item.revealedAtMillis?.let { it >= cutoff(nowMillis) } == true

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
