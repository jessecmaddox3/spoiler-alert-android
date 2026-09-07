package com.jessemaddox.spoileralert.service

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import com.jessemaddox.spoileralert.domain.ConversationIdentity
import java.text.Normalizer

data class ExtractedTexts(val title: String, val body: String, val conversation: String? = null) {
    val combined: String get() = listOfNotNull(title, conversation, body).joinToString("\n")
}

/** One reveal-ready line per chat message: "Sender: text" (or bare text when sender unknown). */
internal fun messageLine(sender: String?, text: String): String =
    if (sender.isNullOrBlank()) text else "$sender: $text"

/** NFC-normalize and straighten typographic quotes so curated aliases match real text. */
internal fun normalizeForMatching(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFC)
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')

/** Stable conversation name for apps that append a rolling message count and latest sender. */
internal fun canonicalConversationTitle(value: String): String =
    ConversationIdentity.canonical(normalizeForMatching(value))

/** Pull every human-readable string out of a notification, including MessagingStyle messages. */
@Suppress("DEPRECATION") // getParcelableArray: type-safe variant is API 33+; fine for v1 at minSdk 26.
fun StatusBarNotification.extractTexts(): ExtractedTexts {
    val e = notification.extras
    val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val parts = mutableListOf<String>()
    // getMessagesFromBundleArray is public API only from 30; below that the latest message is
    // still present in EXTRA_TEXT, so coverage degrades gracefully rather than crashing.
    var parsedMessages = 0
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        e.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { raw ->
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw).forEach { msg ->
                msg.text?.let {
                    parts += messageLine(msg.senderPerson?.name?.toString(), it.toString())
                    parsedMessages++
                }
            }
        }
    }
    // EXTRA_TEXT/BIG_TEXT/SUB_TEXT are legacy summaries of the same messages when
    // MessagingStyle parsed — skip them then, or reveals show the last message twice
    // (once without its sender).
    if (parsedMessages == 0) {
        e.getCharSequence(Notification.EXTRA_TEXT)?.let { parts += it.toString() }
        e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { parts += it.toString() }
        e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { parts += it.toString() }
        notification.tickerText?.let { parts += it.toString() }
    }
    e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts += it.toString() }
    // Group chats carry a conversation title; for 1:1 chats it's usually null and
    // EXTRA_TITLE is the sender — either way it's metadata, safe to show pre-reveal.
    val convTitle = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
    val hasMessaging = e.containsKey(Notification.EXTRA_MESSAGES)
    return ExtractedTexts(
        title = normalizeForMatching(title),
        body = normalizeForMatching(parts.distinct().joinToString("\n")),
        conversation = (convTitle ?: title.takeIf { hasMessaging && it.isNotBlank() })
            ?.let(::canonicalConversationTitle)
            ?.takeIf { it.isNotBlank() },
    )
}

fun StatusBarNotification.hasMessagingStyle(): Boolean =
    notification.extras.containsKey(Notification.EXTRA_MESSAGES)
