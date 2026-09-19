package com.jessemaddox.spoileralert.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPrefsTest {
    @Test
    fun notificationActionTokenIsStableAndRequired() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = AppPrefs.notificationActionToken(context)
        val second = AppPrefs.notificationActionToken(context)

        assertTrue(first.isNotBlank())
        assertTrue(first == second)
        assertTrue(AppPrefs.isValidNotificationActionToken(context, first))
        assertFalse(AppPrefs.isValidNotificationActionToken(context, null))
        assertFalse(AppPrefs.isValidNotificationActionToken(context, "forged"))
    }
}
