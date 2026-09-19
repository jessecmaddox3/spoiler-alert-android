package com.jessemaddox.spoileralert.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Metadata that is safe to retain before reveal. No message text or action token lives here. */
data class NotificationCaptureMetadata(
    val messageCount: Int = 1,
    val isConversation: Boolean = false,
    val isImportantConversation: Boolean = false,
    val sourceCategory: String? = null,
    val hasExactOpen: Boolean = false,
    val hasReplyAction: Boolean = false,
    val hasMarkUnreadAction: Boolean = false,
)

/** Narrow safety bypasses for time-sensitive notifications that must never be swallowed by a
 * sports keyword collision. Message content is intentionally not exempt. */
object NotificationSafety {
    // These platform category values were added after minSdk 26. Compare their stable string
    // values directly so older Android versions can still pass through apps that set them.
    private const val MISSED_CALL_CATEGORY = "missed_call"
    private const val NAVIGATION_CATEGORY = "navigation"
    private val alwaysPassCategories = setOf(
        Notification.CATEGORY_CALL,
        MISSED_CALL_CATEGORY,
        Notification.CATEGORY_ALARM,
        NAVIGATION_CATEGORY,
    )

    fun shouldAlwaysPass(category: String?): Boolean = category in alwaysPassCategories
}

/** Extracts platform-provided conversation and action capabilities. Every value is metadata only;
 * original PendingIntents remain transient and are forwarded separately by [MaskedNotifier]. */
fun StatusBarNotification.captureMetadata(
    ranking: NotificationListenerService.Ranking? = null,
): NotificationCaptureMetadata {
    val actions = notification.actions.orEmpty()
    val messageCount = notification.messageBundles()
        ?.size
        ?.coerceAtLeast(1)
        ?: 1
    val hasMessaging = hasMessagingStyle() || notification.category == Notification.CATEGORY_MESSAGE
    val rankedConversation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ranking?.isConversation == true
    val important = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        ranking?.channel?.isImportantConversation == true
    val reply = actions.any { action ->
        action.remoteInputs?.isNotEmpty() == true ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY)
    }
    val markUnread = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && actions.any {
        it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD
    }
    return NotificationCaptureMetadata(
        messageCount = messageCount,
        isConversation = rankedConversation || hasMessaging,
        isImportantConversation = important,
        sourceCategory = notification.category,
        hasExactOpen = notification.contentIntent != null,
        hasReplyAction = reply,
        hasMarkUnreadAction = markUnread,
    )
}

@Suppress("DEPRECATION")
private fun Notification.messageBundles(): Array<out android.os.Parcelable>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
    } else {
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)
    }
