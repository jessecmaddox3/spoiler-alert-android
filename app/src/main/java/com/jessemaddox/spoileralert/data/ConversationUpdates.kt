package com.jessemaddox.spoileralert.data

/** Local-only merge for chat apps that repost one conversation under changing notification keys. */
object ConversationUpdates {
    fun mergeText(
        previous: String,
        incoming: String,
        previousCount: Int = 1,
        incomingCount: Int = 1,
    ): String {
        val old = previous.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val fresh = incoming.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (old.isEmpty()) return fresh.joinToString("\n")
        if (fresh.isEmpty()) return old.joinToString("\n")
        // Equal text can be either a repost or two genuine identical replies. Android's
        // MessagingStyle count disambiguates the latter without storing any new identifier.
        if (old == fresh && incomingCount > previousCount) return (old + fresh).joinToString("\n")
        if (fresh.size >= old.size && fresh.take(old.size) == old) return fresh.joinToString("\n")
        if (old.size >= fresh.size && old.take(fresh.size) == fresh && incomingCount <= previousCount) {
            return old.joinToString("\n")
        }
        val overlap = (minOf(old.size, fresh.size) downTo 1)
            .firstOrNull { size -> old.takeLast(size) == fresh.take(size) }
            ?: 0
        return (old + fresh.drop(overlap)).joinToString("\n")
    }

    fun mergedMessageCount(previous: VaultEntity, incomingCount: Int, incomingText: String): Int {
        val mergedLines = mergeText(
            previous.text, incomingText, previous.messageCount, incomingCount,
        ).lineSequence().count { it.isNotBlank() }
        return maxOf(previous.messageCount, incomingCount, mergedLines, 1)
    }
}
