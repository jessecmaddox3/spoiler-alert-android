package com.jessemaddox.spoileralert.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationTranscriptTest {
    @Test
    fun `parses sender names from messaging transcript lines`() {
        val lines = parseConversationTranscript(
            "Mira Quill: Reacted 🪁 to a message\nTheo Finch: The paper kite is ready",
        )

        assertEquals("Mira Quill", lines[0].speaker)
        assertEquals(": ", lines[0].separator)
        assertEquals("Reacted 🪁 to a message", lines[0].message)
        assertEquals("Theo Finch", lines[1].speaker)
        assertEquals("The paper kite is ready", lines[1].message)
    }

    @Test
    fun `keeps reply prefix while identifying the actual sender`() {
        val line = parseConversationTranscript(
            "↪ You got a reply: Theo Finch: The paper kite is ready",
        ).single()

        assertEquals("↪ You got a reply: ", line.prefix)
        assertEquals("Theo Finch", line.speaker)
        assertEquals(": ", line.separator)
        assertEquals("The paper kite is ready", line.message)
    }

    @Test
    fun `preserves the original separator around a colon`() {
        val lines = parseConversationTranscript("Rowan:hello\nRowan:  hello")

        assertEquals(":", lines[0].separator)
        assertEquals("hello", lines[0].message)
        assertEquals(":  ", lines[1].separator)
        assertEquals("hello", lines[1].message)
    }

    @Test
    fun `preserves ordinary lines and paragraph breaks`() {
        val lines = parseConversationTranscript("fold the blue paper\n\nattach the string!")

        assertNull(lines[0].speaker)
        assertEquals("fold the blue paper", lines[0].message)
        assertEquals(true, lines[1].isBlank)
        assertEquals("attach the string!", lines[2].message)
    }
}
