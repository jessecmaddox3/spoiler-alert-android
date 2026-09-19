package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationUpdatesTest {
    @Test fun `cumulative message snapshots merge without duplicates`() {
        assertEquals(
            "Mira: first\nTheo: reply",
            ConversationUpdates.mergeText(
                "Mira: first",
                "Mira: first\nTheo: reply",
            ),
        )
    }

    @Test fun `message count reflects distinct merged lines`() {
        val previous = VaultEntity(
            id = 1, shieldId = 2, sourcePackage = "com.whatsapp", sourceAppLabel = "WhatsApp",
            title = "Friends", text = "Mira: first", postedAtMillis = 1,
            notificationKey = "old", conversation = "Friends", messageCount = 1,
            isConversation = true,
        )
        assertEquals(
            2,
            ConversationUpdates.mergedMessageCount(
                previous, 2, "Mira: first\nTheo: reply",
            ),
        )
    }

    @Test fun `identical consecutive messages are preserved when platform count increases`() {
        assertEquals(
            "Mira: ok\nMira: ok",
            ConversationUpdates.mergeText("Mira: ok", "Mira: ok", 1, 2),
        )
    }
}
