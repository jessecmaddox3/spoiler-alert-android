package com.jessemaddox.spoileralert.schedule

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
import com.jessemaddox.spoileralert.data.AppDatabase
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.schedule.PregamePrompt.PromptDecision
import com.jessemaddox.spoileralert.ui.AppPrefs
import com.jessemaddox.spoileralert.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fires ~10 minutes before a cached game's kickoff and ASKS whether to arm the shield.
 * Consent-based: the prompt's "Arm shield" action is the only path to arming — nothing
 * here arms silently. Everything is re-checked at fire time (see [PregamePrompt]) because
 * WorkManager delays are inexact and the world changes between scheduling and firing.
 *
 * Copy shows only public schedule metadata (game short name, kickoff time) plus the
 * shield's name — never vault content.
 */
class PregamePromptWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shieldId = inputData.getLong(KEY_SHIELD_ID, -1L)
        val eventId = inputData.getString(KEY_EVENT_ID)
        if (shieldId == -1L || eventId == null) return Result.failure()

        runCatching {
            val db = AppDatabase.get(applicationContext)
            // Re-derive first (collated review finding 2, call site (c)): fire-time is the
            // last chance before the ask goes out, so a stale name/kickoff gets corrected
            // right before the copy below reads it. No-op for TEAM shields / non-GAME kinds.
            ShieldRepository(applicationContext, db).refreshGameShield(shieldId)
            val shield = db.shieldDao().byId(shieldId)
            val game = db.gameDao().byKey(eventId, shieldId)
            val decision = PregamePrompt.shouldPrompt(
                globalEnabled = AppPrefs.pregamePromptsEnabled(applicationContext),
                shield = shield,
                game = game,
                nowMillis = System.currentTimeMillis(),
            )
            when (decision) {
                is PromptDecision.Skip -> Log.i(TAG, "No prompt for $eventId/$shieldId: ${decision.reason}")
                is PromptDecision.Prompt ->
                    postPrompt(shield!!.name, shield.autoDisarmHours, game!!, decision.alreadyStarted, shieldId)
            }
        }.onFailure { Log.w(TAG, "Pregame prompt failed: $it") }
        return Result.success()
    }

    private fun postPrompt(
        shieldName: String,
        sessionHours: Int,
        game: com.jessemaddox.spoileralert.data.GameEntity,
        alreadyStarted: Boolean,
        shieldId: Long,
    ) {
        val id = notificationId(shieldId)
        val kickoff = timeFormat.format(Instant.ofEpochMilli(game.startMillis).atZone(ZoneId.systemDefault()))
        val title = if (alreadyStarted) "${game.shortName} started at $kickoff"
        else "${game.shortName} starts in 10 min"
        val text = if (alreadyStarted) "Hide $shieldName spoilers until you've watched?"
        else "Kickoff $kickoff · hide $shieldName spoilers until you've watched?"

        val armIntent = PendingIntent.getBroadcast(
            applicationContext, id,
            Intent(applicationContext, ArmShieldReceiver::class.java)
                .setAction(ArmShieldReceiver.ACTION_ARM_SHIELD)
                .putExtra(ArmShieldReceiver.EXTRA_SHIELD_ID, shieldId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            applicationContext, id,
            Intent(applicationContext, MainActivity::class.java)
                .setAction("com.jessemaddox.spoileralert.OPEN_FROM_PREGAME"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, SpoilerAlertApp.CHANNEL_PREGAME)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(applicationContext.getColor(R.color.brand_blue))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(0, "Start hiding ${sessionHours}h", armIntent)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    companion object {
        private const val TAG = "PregamePrompt"
        const val KEY_SHIELD_ID = "shieldId"
        const val KEY_EVENT_ID = "eventId"

        /**
         * Enqueue this worker ~10 minutes before a cached game's kickoff (clamped to "now"
         * when already inside the window; skipped when completed or started too long ago).
         * REPLACE so a reschedule recomputes the delay — see the scheduling rationale on
         * [ScheduleRefreshWorker]. Eligibility is always re-checked at fire time by
         * [PregamePrompt.shouldPrompt], so a stale fire is a harmless skip.
         */
        fun enqueue(context: Context, eventId: String, shieldId: Long, startMillis: Long, completed: Boolean) {
            if (completed) return
            val now = System.currentTimeMillis()
            if (now - startMillis > PregamePrompt.STARTED_GRACE_MS) return
            val fireAt = startMillis - PregamePrompt.LEAD_TIME_MS
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "pregame-$eventId-$shieldId",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<PregamePromptWorker>()
                    .setInitialDelay((fireAt - now).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setInputData(androidx.work.workDataOf(
                        KEY_EVENT_ID to eventId,
                        KEY_SHIELD_ID to shieldId,
                    ))
                    .build(),
            )
        }

        private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

        /** One prompt per shield at a time (a newer game's prompt replaces the older).
         *  Range 40_000-49_999; other id owners: auto-disarm 10_000-14_999,
         *  check-in 15_000-19_999, masked 20_000-39_999, debug 50_000+. */
        fun notificationId(shieldId: Long): Int = 40_000 + (shieldId % 10_000).toInt()
    }
}
