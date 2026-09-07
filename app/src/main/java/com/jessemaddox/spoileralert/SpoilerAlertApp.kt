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

class SpoilerAlertApp : Application() {
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

        // Startup reconciliation invariant (ship-blocker): "no armed shield may exist before
        // onboarding completes." The onboarding live-demo arms the user's REAL team shield for a
        // 1h window; if the app is abandoned mid-demo (system Back with no handler, swipe from
        // recents, or process death), the in-VM teardown never runs and that armed shield keeps
        // silently hiding a brand-new user's real notifications on the next launch. This runs on
        // EVERY process start, BEFORE the notification listener processes anything, and repairs
        // all abandonment paths at once — see [ShieldRepository.reconcileUnonboarded].
        //
        // Timing reasoning (why async off the main thread is safe here):
        //  - onCreate must never do blocking DB work on the main thread (it would jank every
        //    cold start), so the repair runs on [appScope] (Dispatchers.IO).
        //  - The interceptor only HIDES a notification while (a) a shield is armed AND (b) a
        //    matching notification arrives. On a fresh un-onboarded process the ONLY armed shield
        //    is a stranded demo session; reconcileUnonboarded disarms it with a couple of small
        //    IO-thread writes. The interceptor's armedCache is a Room Flow off the shields table,
        //    so the disarm makes it re-emit empty and hiding stops.
        //  - The only exposure is the few-ms window between process start and that disarm
        //    committing, and only if a matching notification is posted in exactly that window.
        //    That is negligible, applies solely to an un-onboarded user (no real armed session
        //    to protect), and is strictly better than the pre-fix state (armed for up to 1h).
        if (!AppPrefs.isOnboarded(this)) {
            appScope.launch { ShieldRepository(this@SpoilerAlertApp).reconcileUnonboarded() }
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
