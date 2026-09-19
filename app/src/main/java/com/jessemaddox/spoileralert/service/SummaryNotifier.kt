package com.jessemaddox.spoileralert.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.data.AppDatabase
import com.jessemaddox.spoileralert.data.ProtectionSessions
import com.jessemaddox.spoileralert.data.SessionToken
import com.jessemaddox.spoileralert.data.RevealSelection
import com.jessemaddox.spoileralert.data.isEffective
import kotlinx.coroutines.sync.withLock
import com.jessemaddox.spoileralert.data.GameWindows
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.WaitingSummary
import com.jessemaddox.spoileralert.ui.AppPrefs
import com.jessemaddox.spoileralert.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wolt Blue accent for all Spoiler Alert notifications (Jess Design System). */
internal val BRAND_BLUE = 0xFF00C2E8.toInt()
internal val ACTIVE_RED = 0xFFC92837.toInt()

/**
 * One persistent, expandable notification group per active event. Each intercepted message is a
 * content-free child posted by [MaskedNotifier]. The summary is ongoing while protection is on,
 * opens that event's controls, and never includes notification content or live score data.
 */
object SummaryNotifier {
    private const val LEGACY_SUMMARY_ID = 1
    private const val WARNING_ID = 2
    private fun formatTime(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

    suspend fun refresh(context: Context) = SessionEffects.mutex.withLock {
        val app = context.applicationContext
        val db = AppDatabase.get(app)
        val now = System.currentTimeMillis()
        val armed = db.shieldDao().protectionRecords().filter { it.isEffective(now) }.map { it.shield }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancel(LEGACY_SUMMARY_ID)

        val activeTags = armed.mapTo(mutableSetOf()) { SessionActions.tag(SessionToken.from(it)!!) }
        nm.activeNotifications.filter {
            (it.id == SessionActions.SUMMARY_ID && it.tag?.startsWith("protection:") == true && it.tag !in activeTags) ||
                (it.tag == null && ProtectionNotificationPolicy.isSummaryId(it.id))
        }.forEach { nm.cancel(it.tag, it.id) }

        armed.forEach { shield ->
            val hiddenCount = WaitingSummary.from(db.vaultDao().hiddenForShield(shield.id)).notificationCount
            val hasGame = shield.gameEventId != null ||
                db.gameDao().upcomingForShield(
                    shield.id, GameWindows.sinceMillis(now),
                ).isNotEmpty()
            post(app, shield, hiddenCount, hasGame, now, ProtectionSessions(db).revealScope(shield.id))
        }
    }

    private fun post(
        context: Context,
        shield: ShieldEntity,
        hiddenCount: Int,
        hasGame: Boolean,
        nowMillis: Long,
        selection: RevealSelection,
    ) {
        val id = SessionActions.SUMMARY_ID
        val token = SessionToken.from(shield) ?: return
        val deadline = ShieldCodec.sessionDeadlineMillis(shield)
        val text = listOfNotNull(
            ProtectionNotificationPolicy.interceptedCopy(hiddenCount),
            deadline?.let { if (it > nowMillis) "Ends ${formatTime(it)}" else
                "Grace ends ${formatTime(ShieldCodec.expiresAtMillis(shield)!!)}" },
        ).joinToString(" · ")
        val controlActivityIntent = Intent(context, MainActivity::class.java)
        if (hasGame) {
            controlActivityIntent
                .setAction(MainActivity.ACTION_OPEN_GAME_STATUS)
                .putExtra(MainActivity.EXTRA_SHIELD_ID, shield.id)
                .putExtra(
                    MainActivity.EXTRA_ACTION_TOKEN,
                    AppPrefs.notificationActionToken(context),
                )
        } else {
            controlActivityIntent.setAction(MainActivity.ACTION_OPEN_HOME)
        }
        val controlIntent = PendingIntent.getActivity(
            context,
            id,
            SessionActions.bind(controlActivityIntent, token, "summary-open"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getActivity(
            context,
            id + 10_000,
            SessionActions.bind(Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_CONFIRM_STOP_PROTECTION)
                .putExtra(MainActivity.EXTRA_SHIELD_ID, shield.id)
                .putExtra(
                    MainActivity.EXTRA_ACTION_TOKEN,
                    AppPrefs.notificationActionToken(context),
                ), token, "summary-stop", selection),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, SpoilerAlertApp.CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(ACTIVE_RED)
            .setContentTitle("Hiding notifications · ${shield.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setTimeoutAfter((ShieldCodec.expiresAtMillis(shield)!! - nowMillis).coerceAtLeast(1))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(controlIntent)
            .setGroup(ProtectionNotificationPolicy.groupKey(shield.id))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .addAction(0, "Stop & reveal", stopIntent)

        if (ProtectionNotificationPolicy.showExtend(deadline, nowMillis)) {
            val extendIntent = PendingIntent.getBroadcast(
                context,
                id,
                SessionActions.bind(Intent(context, ExtendShieldReceiver::class.java)
                    .setAction(ExtendShieldReceiver.ACTION_EXTEND_SHIELD), token, "summary-extend"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "+1 hour", extendIntent)
        }
        context.getSystemService(NotificationManager::class.java).notify(SessionActions.tag(token), id, builder.build())
    }

    fun warnListenerDown(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(WARNING_ID, NotificationCompat.Builder(context, SpoilerAlertApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_shield_off)
            .setColor(BRAND_BLUE)
            .setContentTitle("Hiding stopped")
            .setContentText("Spoiler Alert lost notification access and cannot hide new spoilers.")
            .setContentIntent(intent)
            .addAction(0, "Fix notification access", intent)
            .setAutoCancel(true)
            .build())
    }

    fun clearWarning(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(WARNING_ID)
    }
}
