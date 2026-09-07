package com.jessemaddox.spoileralert.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizationTest {
    @Test fun `curly apostrophe straightened`() {
        assertEquals("the a's win!", normalizeForMatching("the a’s win!"))
    }

    @Test fun `curly double quotes straightened`() {
        assertEquals("\"upset\"", normalizeForMatching("“upset”"))
    }

    @Test fun `nfd input composes to nfc`() {
        // "Hu" + combining diaeresis + "lkenberg" → "Hülkenberg" (single code point ü)
        assertEquals("Hülkenberg", normalizeForMatching("Hülkenberg"))
    }

    @Test fun `plain ascii untouched`() {
        assertEquals("FALCONS WIN 24-17!", normalizeForMatching("FALCONS WIN 24-17!"))
    }
}

class MessageAttributionTest {
    @Test fun `message line prefixes known sender`() {
        assertEquals("Brett Maddox: hi", messageLine("Brett Maddox", "hi"))
    }

    @Test fun `message line without sender is bare text`() {
        assertEquals("hi", messageLine(null, "hi"))
    }

    @Test fun `message line with blank sender is bare text`() {
        assertEquals("hi", messageLine("", "hi"))
    }

    @Test fun `combined includes conversation between title and body`() {
        assertEquals("t\nconv\nb", ExtractedTexts("t", "b", "conv").combined)
    }

    @Test fun `combined without conversation has no blank middle line`() {
        assertEquals("t\nb", ExtractedTexts("t", "b", null).combined)
    }

    @Test fun `rolling chat titles canonicalize to one conversation`() {
        assertEquals("Boys Club", canonicalConversationTitle("Boys Club (3 messages): William"))
        assertEquals("Hank", canonicalConversationTitle("Hank (2 new messages)"))
    }
}
