package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationUpdatesTest {
    @Test fun `cumulative message snapshots merge without duplicates`() {
        assertEquals(
            "Hank: first\nDave: reply",
            ConversationUpdates.mergeText(
                "Hank: first",
                "Hank: first\nDave: reply",
            ),
        )
    }

    @Test fun `message count reflects distinct merged lines`() {
        val previous = VaultEntity(
            id = 1, shieldId = 2, sourcePackage = "com.whatsapp", sourceAppLabel = "WhatsApp",
            title = "Friends", text = "Hank: first", postedAtMillis = 1,
            notificationKey = "old", conversation = "Friends", messageCount = 1,
            isConversation = true,
        )
        assertEquals(
            2,
            ConversationUpdates.mergedMessageCount(
                previous, 2, "Hank: first\nDave: reply",
            ),
        )
    }

    @Test fun `identical consecutive messages are preserved when platform count increases`() {
        assertEquals(
            "Hank: ok\nHank: ok",
            ConversationUpdates.mergeText("Hank: ok", "Hank: ok", 1, 2),
        )
    }
}
