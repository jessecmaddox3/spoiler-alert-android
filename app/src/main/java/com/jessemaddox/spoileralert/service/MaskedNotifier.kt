package com.jessemaddox.spoileralert.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.ui.AppPrefs
import com.jessemaddox.spoileralert.ui.MainActivity

/** One content-free, individually actionable stand-in for every caught notification.
 *  Ids are derived from the vault row's unique DB id: stable across re-posts, range 20_000-39_999.
 *  (Auto-disarm owns 10_000-14_999, check-in 15_000-19_999, pregame 40_000-49_999, debug 50_000+.) */
object MaskedNotifier {

    fun tagFor(vaultRowId: Long) = "vault:$vaultRowId"
    const val NOTICE_ID = 1
    fun idFor(vaultRowId: Long): Int = 20_000 + (vaultRowId % 20_000).toInt()

    fun post(
        context: Context,
        vaultRowId: Long,
        shieldId: Long,
        sourceAppLabel: String,
        conversation: String? = null,
        groupWithEvent: Boolean = true,
        sourceContentIntent: PendingIntent? = null,
    ) {
        val id = idFor(vaultRowId)
        val actionToken = AppPrefs.notificationActionToken(context)
        val openIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_HIDDEN_ITEM)
                .setData(Uri.parse("spoiler-alert://vault/$vaultRowId/open"))
                .putExtra(MainActivity.EXTRA_VAULT_ROW_ID, vaultRowId)
                .putExtra(MainActivity.EXTRA_ACTION_TOKEN, actionToken)
                .apply {
                    sourceContentIntent?.let {
                        putExtra(MainActivity.EXTRA_SOURCE_CONTENT_INTENT, it)
                    }
                },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val revealIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_REVEAL_HIDDEN_ITEM)
                .setData(Uri.parse("spoiler-alert://vault/$vaultRowId/reveal"))
                .putExtra(MainActivity.EXTRA_VAULT_ROW_ID, vaultRowId)
                .putExtra(MainActivity.EXTRA_ACTION_TOKEN, actionToken)
                .apply {
                    sourceContentIntent?.let {
                        putExtra(MainActivity.EXTRA_SOURCE_CONTENT_INTENT, it)
                    }
                },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Sender/conversation is metadata, never spoiler content. Deliberately no shield name:
        // "your team's notification got hidden" is itself a soft signal a game did something.
        val builder = NotificationCompat.Builder(context, SpoilerAlertApp.CHANNEL_CAUGHT)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(BRAND_BLUE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setContentTitle("${conversation ?: sourceAppLabel} notification hidden")
            .setContentText("May contain a spoiler")
            .addAction(0, "Reveal this", revealIntent)
        // The FANTASY sentinel is deliberately never armed, so it cannot own an event summary.
        // Leave those stand-ins ungrouped instead of assigning children to a missing summary.
        if (groupWithEvent) {
            builder
                .setGroup(ProtectionNotificationPolicy.groupKey(shieldId))
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }
        if (conversation != null) builder.setSubText(sourceAppLabel)
        context.getSystemService(NotificationManager::class.java).notify(tagFor(vaultRowId), NOTICE_ID, builder.build())
    }

    fun cancel(context: Context, vaultRowId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(tagFor(vaultRowId), NOTICE_ID)
    }

    fun cancelAll(context: Context, vaultRowIds: List<Long>) {
        vaultRowIds.forEach { cancel(context, it) }
    }

    /** Move a still-hidden stand-in to another event without discarding its trusted intents. */
    fun reassign(context: Context, vaultRowId: Long, newShieldId: Long, groupWithEvent: Boolean = true) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = idFor(vaultRowId)
        val current = runCatching { nm.activeNotifications.firstOrNull { it.tag == tagFor(vaultRowId) && it.id == NOTICE_ID } }
            .getOrNull() ?: return
        val updated = android.app.Notification.Builder.recoverBuilder(context, current.notification)
            .setGroup(if (groupWithEvent) ProtectionNotificationPolicy.groupKey(newShieldId) else null)
            .setGroupAlertBehavior(android.app.Notification.GROUP_ALERT_SUMMARY)
            .build()
        nm.notify(tagFor(vaultRowId), NOTICE_ID, updated)
    }
}
