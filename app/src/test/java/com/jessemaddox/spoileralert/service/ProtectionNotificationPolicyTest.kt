package com.jessemaddox.spoileralert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionNotificationPolicyTest {
    @Test fun `extend appears only during final thirty minutes`() {
        val now = 1_000_000L
        assertFalse(ProtectionNotificationPolicy.showExtend(null, now))
        assertFalse(ProtectionNotificationPolicy.showExtend(now + 31 * 60_000L, now))
        assertTrue(ProtectionNotificationPolicy.showExtend(now + 30 * 60_000L, now))
        assertTrue(ProtectionNotificationPolicy.showExtend(now + 1L, now))
        assertFalse(ProtectionNotificationPolicy.showExtend(now, now))
    }

    @Test fun `intercepted count avoids zero hidden wording`() {
        assertEquals("No notifications hidden yet", ProtectionNotificationPolicy.interceptedCopy(0))
        assertEquals("1 notification hidden", ProtectionNotificationPolicy.interceptedCopy(1))
        assertEquals("3 notifications hidden", ProtectionNotificationPolicy.interceptedCopy(3))
    }

    @Test fun `each shield has stable summary identity and group`() {
        assertEquals(1_042, ProtectionNotificationPolicy.summaryId(42))
        assertEquals("spoiler-alert-shield-42", ProtectionNotificationPolicy.groupKey(42))
        assertTrue(ProtectionNotificationPolicy.isSummaryId(1_042))
        assertFalse(ProtectionNotificationPolicy.isSummaryId(20_042))
    }
}
