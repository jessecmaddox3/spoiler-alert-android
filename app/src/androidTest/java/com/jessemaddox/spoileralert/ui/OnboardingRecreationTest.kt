package com.jessemaddox.spoileralert.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual accessibility surface and Activity recreation, with no provider/device data. */
@RunWith(AndroidJUnit4::class)
class OnboardingRecreationTest {
    @Test fun selectedTeamAndDisclosureSurviveActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val previouslyOnboarded = prefs.getBoolean("onboarded", false)
        prefs.edit().putBoolean("onboarded", false).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                click("NFL")
                click("Arizona Cardinals")
                click("Follow 1 team and continue")
                val disclosure = "To hide Arizona Cardinals spoilers, Spoiler Alert needs notification access."
                awaitNode(disclosure)
                scenario.recreate()
                awaitNode(disclosure)
                // A second recreation must also retain the selection, not just a cached first frame.
                scenario.recreate()
                awaitNode(disclosure)
            }
        } finally {
            prefs.edit().putBoolean("onboarded", previouslyOnboarded).commit()
        }
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            // Modern Android can retain an old Compose label after its selected-state event.
            if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == text) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            find(automation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(50)
        }
        fun visible(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
            listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { visible(node.getChild(it)) }
        throw AssertionError("Expected visible onboarding text: $text. Found: " + visible(automation.rootInActiveWindow).joinToString(" | "))
    }

    private fun click(text: String) {
        var node = awaitNode(text)
        while (!node.isClickable) node = node.parent ?: throw AssertionError("No action for $text")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
