package com.jessemaddox.spoileralert.data

/** Safe, content-free description of what is waiting behind protection. */
data class WaitingSummary(
    val conversationCount: Int,
    val messageCount: Int,
    val otherAlertCount: Int,
    val priorityConversationCount: Int,
    /** Number of intercepted notification records, after Android notification updates are folded. */
    val notificationCount: Int = messageCount + otherAlertCount,
) {
    /** Message-level total retained for conversation catch-up copy and analytics. */
    val totalItemCount: Int get() = messageCount + otherAlertCount

    fun compactCopy(): String {
        val parts = buildList {
            if (conversationCount > 0) {
                add("$conversationCount conversation${if (conversationCount == 1) "" else "s"}")
            }
            if (otherAlertCount > 0) {
                add("$otherAlertCount other alert${if (otherAlertCount == 1) "" else "s"}")
            }
        }
        return when {
            parts.isEmpty() -> "Nothing is waiting"
            parts.size == 1 -> "${parts.single()} waiting"
            else -> "${parts.dropLast(1).joinToString(", ")} and ${parts.last()} waiting"
        }
    }

    companion object {
        fun from(items: List<VaultEntity>): WaitingSummary {
            val conversations = items.filter { it.isConversation || !it.conversation.isNullOrBlank() }
                .groupBy { it.sourcePackage to it.conversation.orEmpty() }
            val conversationItems = conversations.values.flatten()
            val messageCount = conversationItems.sumOf { it.messageCount.coerceAtLeast(1) }
            return WaitingSummary(
                conversationCount = conversations.size,
                messageCount = messageCount,
                otherAlertCount = items.size - conversationItems.size,
                priorityConversationCount = conversations.values.count { group ->
                    group.any { it.isImportantConversation }
                },
                notificationCount = items.size,
            )
        }
    }
}
