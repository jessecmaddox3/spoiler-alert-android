package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRangeTest {
    @Test fun `empty-state copy is natural for each range`() {
        assertEquals("No notifications revealed this week.", HistoryRange.WEEK.emptyCopy)
        assertEquals("No notifications revealed in the last 30 days.", HistoryRange.MONTH.emptyCopy)
        assertEquals("No notifications revealed in the last year.", HistoryRange.YEAR.emptyCopy)
    }

    private val day = 24L * 60 * 60 * 1000
    private val now = 2_000_000_000_000L

    @Test fun `week month and year filters use reveal time`() {
        val recent = item(posted = now - 200 * day, revealed = now - 3 * day)
        val older = item(posted = now - 200 * day, revealed = now - 20 * day)
        val old = item(posted = now - 300 * day, revealed = now - 200 * day)

        assertTrue(HistoryRange.WEEK.includes(recent, now))
        assertFalse(HistoryRange.WEEK.includes(older, now))
        assertTrue(HistoryRange.MONTH.includes(older, now))
        assertTrue(HistoryRange.YEAR.includes(old, now))
    }

    @Test fun `still-hidden items never appear in history`() {
        val hidden = item(now - day, now).copy(revealedAtMillis = null)
        assertFalse(HistoryRange.YEAR.includes(hidden, now))
    }

    private fun item(posted: Long, revealed: Long) = VaultEntity(
        id = posted, shieldId = 1, sourcePackage = "p", sourceAppLabel = "App",
        title = "t", text = "x", postedAtMillis = posted, notificationKey = "k$posted",
        revealedAtMillis = revealed,
    )
}
