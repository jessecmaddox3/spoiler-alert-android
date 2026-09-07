package com.jessemaddox.spoileralert.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationTranscriptTest {
    @Test
    fun `parses sender names from messaging transcript lines`() {
        val lines = parseConversationTranscript(
            "Shovon Kibria: Reacted 😂 to a message\nDavid Lloyd: Hahaha hilarious",
        )

        assertEquals("Shovon Kibria", lines[0].speaker)
        assertEquals(": ", lines[0].separator)
        assertEquals("Reacted 😂 to a message", lines[0].message)
        assertEquals("David Lloyd", lines[1].speaker)
        assertEquals("Hahaha hilarious", lines[1].message)
    }

    @Test
    fun `keeps reply prefix while identifying the actual sender`() {
        val line = parseConversationTranscript(
            "↪ You got a reply: David Lloyd: Hahaha hilarious",
        ).single()

        assertEquals("↪ You got a reply: ", line.prefix)
        assertEquals("David Lloyd", line.speaker)
        assertEquals(": ", line.separator)
        assertEquals("Hahaha hilarious", line.message)
    }

    @Test
    fun `preserves the original separator around a colon`() {
        val lines = parseConversationTranscript("Kait:hello\nKait:  hello")

        assertEquals(":", lines[0].separator)
        assertEquals("hello", lines[0].message)
        assertEquals(":  ", lines[1].separator)
        assertEquals("hello", lines[1].message)
    }

    @Test
    fun `preserves ordinary lines and paragraph breaks`() {
        val lines = parseConversationTranscript("looked it up\n\nno words!")

        assertNull(lines[0].speaker)
        assertEquals("looked it up", lines[0].message)
        assertEquals(true, lines[1].isBlank)
        assertEquals("no words!", lines[2].message)
    }
}
