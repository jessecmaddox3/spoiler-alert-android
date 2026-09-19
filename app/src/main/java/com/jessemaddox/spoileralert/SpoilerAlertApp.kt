package com.jessemaddox.spoileralert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.ui.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import android.util.Log

class SpoilerAlertApp : Application() {
    val startupReady = CompletableDeferred<Unit>()
    /** App-scoped, IO-bound: for the un-onboarded startup reconciliation only. Lives as long as
     *  the process, so it is never cancelled out from under the short repair coroutine. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, "Session status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CAUGHT, "Hidden notifications", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One content-free notice for each notification Spoiler Alert hides"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Hiding problems", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Session results", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DEBUG_FAKE, "Debug fake spoilers", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PREGAME, "Game reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHECKIN, "Session deadlines", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETION, "Event finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reminder to stop hiding after a public event is confirmed over"
            }
        )
        // Remove the obsolete pre-v3.5 stand-in channel. Current caught-item notices use
        // CHANNEL_CAUGHT; lifecycle state remains separately on CHANNEL_SUMMARY.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_MASKED)

        // The listener publishes no cache until stranded demo and scheduler state are repaired.
        appScope.launch {
            try {
                val repo = ShieldRepository(this@SpoilerAlertApp)
                if (!AppPrefs.isOnboarded(this@SpoilerAlertApp)) repo.reconcileUnonboarded()
                repo.reconcileSessions()
                startupReady.complete(Unit)
            } catch (error: Exception) {
                Log.e("SpoilerAlert", "Startup repair failed", error)
                startupReady.completeExceptionally(error)
            }
        }
    }

    companion object {
        const val CHANNEL_SUMMARY = "summary"
        const val CHANNEL_CAUGHT = "caught_items"
        const val CHANNEL_STATUS = "status"
        /** Session outcomes and catch-up summaries. */
        const val CHANNEL_RESULTS = "results"
        const val CHANNEL_DEBUG_FAKE = "debug_fake"
        const val CHANNEL_PREGAME = "pregame"
        const val CHANNEL_CHECKIN = "checkin"
        const val CHANNEL_COMPLETION = "completion"
        private const val LEGACY_CHANNEL_MASKED = "masked"
    }
}
