package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.domain.ConversationIdentity

/**
 * A run of hidden/revealed notifications from one chat or one app, within a session group.
 *
 * @property conversationLabel the chat/sender name, or null when the source has no conversation
 *   (rendered app-only, e.g. "ESPN · 3 hidden").
 * @property items sorted oldest-first so a burst reads chronologically once expanded/revealed.
 */
data class ConversationGroup(
    val sourcePackage: String,
    val sourceAppLabel: String,
    val conversationLabel: String?,
    val items: List<VaultEntity>,
) {
    val count: Int get() = items.size
    val messageCount: Int get() = maxOf(
        items.size,
        items.maxOfOrNull { it.messageCount.coerceAtLeast(1) } ?: 0,
        items.maxOfOrNull {
            maxOf(
                ConversationIdentity.rollingMessageCount(it.conversation) ?: 0,
                ConversationIdentity.rollingMessageCount(it.title) ?: 0,
            )
        } ?: 0,
    )
    /** Older app versions stored rolling cumulative chat snapshots as separate rows. History
     * shows the most complete snapshot once; current interception consolidates them at write time. */
    val displayItems: List<VaultEntity> get() {
        if (items.size <= 1) return items
        val mostComplete = items.maxWithOrNull(
            compareBy<VaultEntity> { it.messageCount }.thenBy { it.postedAtMillis }
        )
        return if ((mostComplete?.messageCount ?: 1) > 1) listOfNotNull(mostComplete) else items
    }
    val isConversation: Boolean get() = items.any {
        it.isConversation || !it.conversation.isNullOrBlank()
    }
    val isImportantConversation: Boolean get() = items.any { it.isImportantConversation }
    val firstPostedAt: Long get() = items.first().postedAtMillis
    val lastPostedAt: Long get() = items.last().postedAtMillis

    /** "WhatsApp · Family chat" or just "ESPN" when there is no conversation. */
    fun heading(): String =
        listOfNotNull(sourceAppLabel, conversationLabel?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** One shield/session's hidden items, sub-grouped by conversation (v3 amendment item 6). */
data class SessionGroup(
    val shieldId: Long,
    val conversations: List<ConversationGroup>,
) {
    val count: Int get() = conversations.sumOf { it.count }
    val messageCount: Int get() = conversations.sumOf { it.messageCount }
    val isConversation: Boolean get() = conversations.any { it.isConversation }
    val isImportantConversation: Boolean get() = conversations.any { it.isImportantConversation }
    val lastPostedAt: Long get() = conversations.maxOf { it.lastPostedAt }
}

/**
 * Pure grouping for the Hidden tab and History screen. Two axes, both preserved:
 *  - session axis (shieldId) → [SessionGroup], which keeps the session-scoped "reveal all & stop"
 *  - conversation axis (app + chat) → [ConversationGroup], for readable catch-up
 *
 * Never inspects title/text — grouping is metadata-only, safe to run on still-hidden rows.
 */
object HiddenGrouping {

    /** Top level: split by shield, then by conversation. Sessions ordered most-recent first. */
    fun group(items: List<VaultEntity>): List<SessionGroup> =
        items.groupBy { it.shieldId }
            .map { (shieldId, shieldItems) -> SessionGroup(shieldId, groupByConversation(shieldItems)) }
            .sortedWith(
                compareByDescending<SessionGroup> { it.isImportantConversation }
                    .thenByDescending { it.isConversation }
                    .thenByDescending { it.messageCount }
                    .thenByDescending { it.lastPostedAt }
            )

    /**
     * Group by conversation (or by source app when the notification has no conversation).
     * Items within a conversation are sorted oldest-first; conversations are ordered by their
     * most-recent item, newest first. Used directly by History (across all sessions).
     */
    fun groupByConversation(items: List<VaultEntity>): List<ConversationGroup> =
        items.groupBy { keyOf(it) }
            .map { (_, group) ->
                val sorted = group.sortedBy { it.postedAtMillis }
                val head = sorted.first()
                ConversationGroup(
                    sourcePackage = head.sourcePackage,
                    sourceAppLabel = head.sourceAppLabel,
                    conversationLabel = head.conversation
                        ?.takeIf { it.isNotBlank() }
                        ?.let(ConversationIdentity::canonical),
                    items = sorted,
                )
            }
            .sortedWith(
                compareByDescending<ConversationGroup> { it.isImportantConversation }
                    .thenByDescending { it.isConversation }
                    .thenByDescending { it.messageCount }
                    .thenByDescending { it.lastPostedAt }
            )

    /** Named conversations key on (package, chat); conversation-less rows key on app label. */
    private fun keyOf(v: VaultEntity): Pair<String, String> {
        val convo = v.conversation?.takeIf { it.isNotBlank() }
            ?.let(ConversationIdentity::canonical)
        return if (convo != null) v.sourcePackage to "c:$convo" else v.sourceAppLabel to "a:"
    }

    /**
     * "8:41–9:12 PM" — the two endpoint times, dropping the leading time's meridiem when both
     * share it. Same rendered minute collapses to a single time. [format] renders one instant
     * (injected so the math is locale-agnostic and testable).
     */
    fun timeRange(firstMillis: Long, lastMillis: Long, format: (Long) -> String): String {
        val a = format(firstMillis)
        val b = format(lastMillis)
        if (a == b) return a
        val aMeridiem = a.substringAfterLast(' ', "")
        val bMeridiem = b.substringAfterLast(' ', "")
        val start = if (aMeridiem.isNotEmpty() && aMeridiem == bMeridiem) a.substringBeforeLast(' ') else a
        return "$start–$b"
    }

    /** TalkBack phrasing: "between 8:41 PM and 9:12 PM" / "at 8:41 PM" (same minute). */
    fun spokenTimeRange(firstMillis: Long, lastMillis: Long, format: (Long) -> String): String {
        val a = format(firstMillis)
        val b = format(lastMillis)
        return if (a == b) "at $a" else "between $a and $b"
    }
}
