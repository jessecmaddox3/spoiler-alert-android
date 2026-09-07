package com.jessemaddox.spoileralert.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.data.WaitingSummary
import com.jessemaddox.spoileralert.ui.AppPrefs
import com.jessemaddox.spoileralert.ui.MainActivity

/**
 * Safe post-release catch-up surface. It never includes intercepted title/text. Each child is
 * one conversation (or one source app), and reuses the former stand-in PendingIntent when it is
 * still available so the revealed item can offer the source notification's exact destination.
 */
object CatchUpNotifier {
    private const val CHILD_BASE = 60_000
    private const val SUMMARY_BASE = 80_000

    fun post(context: Context, shieldId: Long, shieldName: String, released: List<VaultEntity>) {
        if (released.isEmpty()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val oldContentIntents = runCatching {
            nm.activeNotifications.associate { it.id to it.notification.contentIntent }
        }.getOrDefault(emptyMap())
        val groups = released.groupBy { item ->
            item.sourcePackage to item.conversation.orEmpty().ifBlank { "__app__" }
        }.values.sortedWith(
            compareByDescending<List<VaultEntity>> { group -> group.any { it.isImportantConversation } }
                .thenByDescending { group -> group.any { it.isConversation || !it.conversation.isNullOrBlank() } }
                .thenByDescending { group -> group.sumOf { it.messageCount.coerceAtLeast(1) } }
                .thenByDescending { group -> group.maxOf { it.postedAtMillis } }
        )
        val notificationGroup = "catch-up-$shieldId"
        groups.forEachIndexed { index, group ->
            val newest = group.maxBy { it.postedAtMillis }
            val messages = group.sumOf { it.messageCount.coerceAtLeast(1) }
            val heading = newest.conversation?.takeIf { it.isNotBlank() } ?: newest.sourceAppLabel
            val open = oldContentIntents[MaskedNotifier.idFor(newest.id)] ?: historyIntent(context, shieldId)
            val child = NotificationCompat.Builder(context, SpoilerAlertApp.CHANNEL_RESULTS)
                .setSmallIcon(R.drawable.ic_shield)
                .setColor(BRAND_BLUE)
                .setContentTitle(heading)
                .setContentText(
                    if (messages == 1) "1 message is ready to catch up"
                    else "$messages messages are ready to catch up"
                )
                .setSubText(newest.sourceAppLabel)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(notificationGroup)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .build()
            nm.notify(childId(shieldId, index), child)
        }

        val summary = WaitingSummary.from(released)
        nm.notify(
            summaryId(shieldId),
            NotificationCompat.Builder(context, SpoilerAlertApp.CHANNEL_RESULTS)
                .setSmallIcon(R.drawable.ic_shield)
                .setColor(BRAND_BLUE)
                .setContentTitle("Ready to catch up: $shieldName")
                .setContentText(summary.compactCopy())
                .setContentIntent(historyIntent(context, shieldId))
                .setAutoCancel(true)
                .setGroup(notificationGroup)
                .setGroupSummary(true)
                .build(),
        )
    }

    private fun historyIntent(context: Context, shieldId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            summaryId(shieldId),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_HISTORY)
                .putExtra(MainActivity.EXTRA_ACTION_TOKEN, AppPrefs.notificationActionToken(context)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun childId(shieldId: Long, index: Int): Int =
        CHILD_BASE + (((shieldId * 31) + index) % 19_000).toInt()

    private fun summaryId(shieldId: Long): Int = SUMMARY_BASE + (shieldId % 10_000).toInt()
}
