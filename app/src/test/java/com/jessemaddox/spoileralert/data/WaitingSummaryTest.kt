package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitingSummaryTest {
    @Test fun `updated messaging notification counts its contained messages`() {
        val summary = WaitingSummary.from(listOf(
            item(1, "WhatsApp", "Family", messages = 4, important = true),
            item(2, "ESPN", null),
        ))

        assertEquals(1, summary.conversationCount)
        assertEquals(4, summary.messageCount)
        assertEquals(1, summary.otherAlertCount)
        assertEquals(1, summary.priorityConversationCount)
        assertEquals(2, summary.notificationCount)
        assertEquals(5, summary.totalItemCount)
        assertEquals("1 conversation and 1 other alert waiting", summary.compactCopy())
    }

    @Test fun `several rows from one chat remain one conversation`() {
        val summary = WaitingSummary.from(listOf(
            item(1, "Messages", "Paper Kite Workshop", messages = 2),
            item(2, "Messages", "Paper Kite Workshop", messages = 3),
        ))
        assertEquals(1, summary.conversationCount)
        assertEquals(5, summary.messageCount)
        assertEquals(2, summary.notificationCount)
        assertEquals("1 conversation waiting", summary.compactCopy())
    }

    @Test fun `empty summary is explicit`() {
        assertEquals("Nothing is waiting", WaitingSummary.from(emptyList()).compactCopy())
    }

    private fun item(
        id: Long,
        app: String,
        conversation: String?,
        messages: Int = 1,
        important: Boolean = false,
    ) = VaultEntity(
        id = id,
        shieldId = 1,
        sourcePackage = "pkg.$app",
        sourceAppLabel = app,
        title = "title",
        text = "body",
        postedAtMillis = id,
        notificationKey = "key-$id",
        conversation = conversation,
        messageCount = messages,
        isConversation = conversation != null,
        isImportantConversation = important,
    )
}
