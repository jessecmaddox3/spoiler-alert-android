package com.jessemaddox.spoileralert.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.data.WaitingSummary
import com.jessemaddox.spoileralert.ui.MainActivity
import kotlin.math.roundToInt

/**
 * Fires ~15 minutes before the selected session window ends and ASKS whether to keep hiding.
 * The forgotten-shield fix: instead of silently hiding messages for hours past the game,
 * the user gets one chance to extend. No answer activates the fixed one-hour grace, then
 * [AutoDisarmWorker] stops interception while keeping everything sealed.
 *
 * Everything is re-checked at fire time (see [CheckIn]) because WorkManager delays are
 * inexact and re-arming/extending REPLACEs this work with a later copy.
 */
class CheckInWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shieldId = inputData.getLong(KEY_SHIELD_ID, -1L)
        if (shieldId == -1L) return Result.failure()

        runCatching {
            val repo = ShieldRepository(applicationContext)
            val shield = repo.shieldDao.byId(shieldId)
            if (!CheckIn.shouldCheckIn(shield, System.currentTimeMillis())) {
                Log.i(TAG, "Skipping check-in for $shieldId: not armed or expiry moved")
                return@runCatching
            }
            val waiting = WaitingSummary.from(repo.vaultDao.hiddenForShield(shieldId))
            // Refresh the persistent event summary inside the final 30-minute window so its
            // +1 hour action appears even when no new notification has been intercepted.
            SummaryNotifier.refresh(applicationContext)
            postCheckIn(shield!!.name, shield.armedAtMillis, shield.autoDisarmHours, shieldId, waiting)
        }.onFailure { Log.w(TAG, "Check-in failed: $it") }
        return Result.success()
    }

    private fun postCheckIn(
        shieldName: String,
        armedAtMillis: Long?,
        sessionHours: Int,
        shieldId: Long,
        waiting: WaitingSummary,
    ) {
        val id = notificationId(shieldId)
        val hidingHours = armedAtMillis
            ?.let { ((System.currentTimeMillis() - it) / 3_600_000.0).roundToInt() }
            ?.coerceAtLeast(1) ?: sessionHours

        val extendIntent = PendingIntent.getBroadcast(
            applicationContext, id,
            Intent(applicationContext, ExtendShieldReceiver::class.java)
                .setAction(ExtendShieldReceiver.ACTION_EXTEND_SHIELD)
                .putExtra(ExtendShieldReceiver.EXTRA_SHIELD_ID, shieldId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val revealIntent = PendingIntent.getBroadcast(
            applicationContext, id + 5_000, // distinct request code so the two actions never collide
            Intent(applicationContext, RevealShieldReceiver::class.java)
                .setAction(RevealShieldReceiver.ACTION_REVEAL_SHIELD)
                .putExtra(RevealShieldReceiver.EXTRA_SHIELD_ID, shieldId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            applicationContext, id,
            Intent(applicationContext, MainActivity::class.java)
                .setAction("com.jessemaddox.spoileralert.OPEN_FROM_CHECKIN"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // "Stop blocking and reveal" IS explicit consent — the reveal shortcut that survives the
        // sealed-vault rule. No answer = disarm + seal (AutoDisarmWorker), never reveal.
        val notification = NotificationCompat.Builder(applicationContext, SpoilerAlertApp.CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(BRAND_BLUE)
            .setContentTitle("Session check-in")
            .setContentText("$shieldName · ${waiting.compactCopy()} · ends in 15 min")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$shieldName · ${waiting.compactCopy()}${if (waiting.priorityConversationCount > 0) ", including a priority conversation" else ""}. Active for ${hidingHours}h. No answer keeps hiding on for a one-hour grace, then everything stays hidden until you reveal it."
            ))
            .setContentIntent(openIntent)
            .addAction(0, "+1 hour", extendIntent)
            .addAction(0, "Stop & reveal", revealIntent)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    companion object {
        private const val TAG = "CheckInWorker"
        const val KEY_SHIELD_ID = "shieldId"

        /** One check-in per shield at a time. Range 15_000-19_999; other id owners:
         *  auto-disarm 10_000-14_999, masked 20_000-39_999, pregame 40_000-49_999, debug 50_000+. */
        fun notificationId(shieldId: Long): Int = 15_000 + (shieldId % 5_000).toInt()

        fun cancelNotification(context: Context, shieldId: Long) {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(shieldId))
        }
    }
}
