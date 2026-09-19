package com.jessemaddox.spoileralert.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.data.SessionToken
import com.jessemaddox.spoileralert.data.RevealSelection
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.ui.MainActivity
import com.jessemaddox.spoileralert.widget.SpoilerTileService
import com.jessemaddox.spoileralert.widget.WidgetRefresher
import java.util.concurrent.TimeUnit

class AutoDisarmWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /** Fires once at the absolute protection deadline, after the one-hour grace window. */
    override suspend fun doWork(): Result {
        (applicationContext as SpoilerAlertApp).startupReady.await()
        val token = SessionActions.token(inputData) ?: return Result.success()
        val repo = ShieldRepository(applicationContext)
        repo.expire(token) { outcome, selection ->
            postSessionEnded(token, selection, outcome.name, outcome.waitingSummary.notificationCount)
        }
        SummaryNotifier.refresh(applicationContext)
        WidgetRefresher.refreshNow(applicationContext)
        SpoilerTileService.requestUpdate(applicationContext)
        return Result.success()
    }

    /** "Hiding stopped · N notifications are still hidden". Content-tap opens Spoilers; the
     *  reveal path from here remains an explicit user action. */
    private fun postSessionEnded(token: SessionToken, selection: RevealSelection, name: String, sealedCount: Int) {
        val id = SessionActions.END_ID
        val openHidden = PendingIntent.getActivity(
            applicationContext, id,
            SessionActions.bind(Intent(applicationContext, MainActivity::class.java)
                .setAction("com.jessemaddox.spoileralert.SESSION_ENDED")
                .putExtra("openVault", true), token, "ended-open"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(applicationContext, SpoilerAlertApp.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_shield_off)
            .setColor(BRAND_BLUE)
            .setContentTitle(SessionEndCopy.TITLE)
            .setContentText(SessionEndCopy.text(name, sealedCount))
            .setContentIntent(openHidden)
            .setAutoCancel(true)
        if (sealedCount > 0) {
            val revealAll = PendingIntent.getBroadcast(
                applicationContext, id + 5_000, // distinct request code from the content intent
                SessionActions.bind(Intent(applicationContext, RevealShieldReceiver::class.java)
                    .setAction(RevealShieldReceiver.ACTION_REVEAL_SHIELD), token, "ended-reveal", selection),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Reveal notifications", revealAll)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(SessionActions.tag(token), id, builder.build())
    }

    companion object {
        const val KEY_SHIELD_ID = "shieldId"

        fun schedule(context: Context, token: SessionToken, delayMillis: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SessionActions.workName("auto-disarm", token),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoDisarmWorker>()
                    .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setInputData(SessionActions.data(token))
                    .build(),
            )
        }

        /** Range 10_000-14_999; other id owners: check-in 15_000-19_999,
         *  masked 20_000-39_999, pregame 40_000-49_999, debug 50_000+. */
        fun notificationId(shieldId: Long): Int = 10_000 + (shieldId % 5_000).toInt()
    }
}
