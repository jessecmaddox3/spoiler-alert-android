package com.jessemaddox.spoileralert.service

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSafetyTest {
    @Test fun `calls and missed calls always pass`() {
        assertTrue(NotificationSafety.shouldAlwaysPass(Notification.CATEGORY_CALL))
        assertTrue(NotificationSafety.shouldAlwaysPass(Notification.CATEGORY_MISSED_CALL))
    }

    @Test fun `alarms and navigation always pass`() {
        assertTrue(NotificationSafety.shouldAlwaysPass(Notification.CATEGORY_ALARM))
        assertTrue(NotificationSafety.shouldAlwaysPass(Notification.CATEGORY_NAVIGATION))
    }

    @Test fun `messages and sports updates remain eligible for protection`() {
        assertFalse(NotificationSafety.shouldAlwaysPass(Notification.CATEGORY_MESSAGE))
        assertFalse(NotificationSafety.shouldAlwaysPass(Notification.CATEGORY_SOCIAL))
        assertFalse(NotificationSafety.shouldAlwaysPass(null))
    }
}
