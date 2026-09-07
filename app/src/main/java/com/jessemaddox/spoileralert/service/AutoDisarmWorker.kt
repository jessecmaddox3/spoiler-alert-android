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
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.ui.MainActivity
import com.jessemaddox.spoileralert.widget.SpoilerTileService
import com.jessemaddox.spoileralert.widget.WidgetRefresher
import java.util.concurrent.TimeUnit

class AutoDisarmWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /** Fires once at the absolute protection deadline, after the one-hour grace window. */
    override suspend fun doWork(): Result {
        val shieldId = inputData.getLong(KEY_SHIELD_ID, -1L)
        if (shieldId == -1L) return Result.failure()
        val repo = ShieldRepository(applicationContext)

        // armed == true here always implies armedAtMillis is set — arm() and disarmAndReveal
        // always write armed/armedAtMillis together. This is the session snapshot
        // GraceDecision just validated against "now".
        val current = repo.shieldDao.byId(shieldId)
        val now = System.currentTimeMillis()
        when (GraceDecision.decide(current, now)) {
            GraceDecision.Outcome.NOOP -> {
                // A worker from the pre-extension deadline can still run if the process died
                // after the deadline CAS but before WorkManager's REPLACE was enqueued. Repair
                // that durable-state/scheduler gap from the authoritative Room deadline.
                val futureExpiry = current?.takeIf { it.armed }
                    ?.let(ShieldCodec::expiresAtMillis)
                    ?.takeIf { it > now }
                if (futureExpiry != null) schedule(applicationContext, shieldId, futureExpiry - now)
                return Result.success()
            }

            // Grace ended, still unanswered: stop interception and keep everything sealed.
            //
            // CONSENT RULE: an unanswered expiry stops intercepting but keeps the vault SEALED.
            // Reveal happens only from an explicit user action (the notification's "Reveal
            // all", the Hidden tab, or a check-in answer).
            GraceDecision.Outcome.SEAL -> {
                val expectedArmedAt = current!!.armedAtMillis ?: return Result.success()
                // Compare-and-swap: a concurrent explicit answer wins and this bails without
                // posting the notification below (see ShieldDao.disarmIfArmedAt).
                val outcome = repo.disarmIfUnchangedKeepSealed(shieldId, expectedArmedAt)
                    ?: return Result.success()

                if (outcome.hiddenCount == 0) {
                    // Nothing to reveal (collated review finding 4): a spent GAME shield
                    // that sealed with an empty vault should clean itself up exactly like
                    // the explicit reveal path does, instead of lingering because nobody
                    // ever visits a "sealed" state that has nothing sealed in it. The
                    // notification below already uses SessionEndCopy's zero-count copy
                    // ("… no notifications were hidden", no reveal action) — this is purely the
                    // shield-lifecycle side of the same case. No-op for TEAM/CUSTOM shields.
                    repo.cleanupGameShield(shieldId)
                }
                SummaryNotifier.refresh(applicationContext)
                WidgetRefresher.refreshNow(applicationContext)
                SpoilerTileService.requestUpdate(applicationContext)
                postSessionEnded(
                    shieldId,
                    outcome.name,
                    outcome.waitingSummary.notificationCount,
                )
                return Result.success()
            }
        }
    }

    /** "Hiding stopped · N notifications are still hidden". Content-tap opens Spoilers; the
     *  reveal path from here remains an explicit user action. */
    private fun postSessionEnded(shieldId: Long, name: String, sealedCount: Int) {
        val id = notificationId(shieldId)
        val openHidden = PendingIntent.getActivity(
            applicationContext, id,
            Intent(applicationContext, MainActivity::class.java)
                .setAction("com.jessemaddox.spoileralert.SESSION_ENDED")
                .putExtra("openVault", true),
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
                Intent(applicationContext, RevealShieldReceiver::class.java)
                    .setAction(RevealShieldReceiver.ACTION_REVEAL_SHIELD)
                    .putExtra(RevealShieldReceiver.EXTRA_SHIELD_ID, shieldId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Reveal notifications", revealAll)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(id, builder.build())
    }

    companion object {
        const val KEY_SHIELD_ID = "shieldId"

        fun schedule(context: Context, shieldId: Long, delayMillis: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "auto-disarm-$shieldId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoDisarmWorker>()
                    .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_SHIELD_ID to shieldId))
                    .build(),
            )
        }

        /** Range 10_000-14_999; other id owners: check-in 15_000-19_999,
         *  masked 20_000-39_999, pregame 40_000-49_999, debug 50_000+. */
        fun notificationId(shieldId: Long): Int = 10_000 + (shieldId % 5_000).toInt()
    }
}
