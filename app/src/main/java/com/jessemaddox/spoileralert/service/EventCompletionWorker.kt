package com.jessemaddox.spoileralert.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.data.EventCompletionTarget
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.data.WaitingSummary
import com.jessemaddox.spoileralert.schedule.LiveScoreFetcher
import com.jessemaddox.spoileralert.ui.AppPrefs
import com.jessemaddox.spoileralert.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Checks one public event near its expected finish. A confirmed final produces a content-free
 * reminder so protection is not accidentally left on. It never stops or reveals automatically.
 */
class EventCompletionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shieldId = inputData.getLong(KEY_SHIELD_ID, -1L)
        if (shieldId < 0) return Result.failure()
        val repo = ShieldRepository(applicationContext)
        val target = repo.eventCompletionTarget(shieldId) ?: return Result.success()
        val now = System.currentTimeMillis()
        val complete = runCatching {
            LiveScoreFetcher().fetch(
                target.leagueId,
                target.eventId,
                target.startMillis,
                forceRefresh = true,
            )?.let { it.completed || it.statusState == "post" }
        }.onFailure { Log.w(TAG, "Completion check failed for public event ${target.eventId}", it) }
            .getOrNull()

        if (complete == true) {
            postCompleted(target, WaitingSummary.from(repo.vaultDao.hiddenForShield(shieldId)))
        } else {
            EventCompletionPolicy.nextDelayMillis(
                complete,
                now,
                target.protectionDeadlineMillis,
            )?.let { schedule(applicationContext, shieldId, it) }
        }
        return Result.success()
    }

    private fun postCompleted(target: EventCompletionTarget, waiting: WaitingSummary) {
        val id = notificationId(target.shieldId)
        val token = AppPrefs.notificationActionToken(applicationContext)
        val openStatus = PendingIntent.getActivity(
            applicationContext,
            id,
            Intent(applicationContext, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_GAME_STATUS)
                .putExtra(MainActivity.EXTRA_SHIELD_ID, target.shieldId)
                .putExtra(MainActivity.EXTRA_ACTION_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getActivity(
            applicationContext,
            id + 5_000,
            Intent(applicationContext, MainActivity::class.java)
                .setAction(MainActivity.ACTION_CONFIRM_STOP_PROTECTION)
                .putExtra(MainActivity.EXTRA_SHIELD_ID, target.shieldId)
                .putExtra(MainActivity.EXTRA_ACTION_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        applicationContext.getSystemService(NotificationManager::class.java).notify(
            id,
            NotificationCompat.Builder(applicationContext, SpoilerAlertApp.CHANNEL_COMPLETION)
                .setSmallIcon(R.drawable.ic_shield)
                .setColor(BRAND_BLUE)
                .setContentTitle("${target.shieldName} is over")
                .setContentText("Still hiding notifications · ${waiting.compactCopy()}")
                .setContentIntent(openStatus)
                .addAction(0, "Stop & reveal", stop)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val TAG = "EventCompletion"
        private const val KEY_SHIELD_ID = "shieldId"
        fun schedule(context: Context, shieldId: Long, delayMillis: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(shieldId),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EventCompletionWorker>()
                    .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_SHIELD_ID to shieldId))
                    .build(),
            )
        }

        fun cancel(context: Context, shieldId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(shieldId))
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(shieldId))
        }

        private fun workName(shieldId: Long) = "event-completion-$shieldId"
        fun notificationId(shieldId: Long): Int = 90_000 + (shieldId % 5_000).toInt()
    }
}
