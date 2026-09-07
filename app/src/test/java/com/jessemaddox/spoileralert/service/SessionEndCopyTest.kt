package com.jessemaddox.spoileralert.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEndCopyTest {

    @Test
    fun `title never mentions reveal - sealed is the rule`() {
        assertEquals("Hiding stopped", SessionEndCopy.TITLE)
    }

    @Test
    fun `zero sealed items says nothing was hidden`() {
        assertEquals("Falcons · no notifications were hidden", SessionEndCopy.text("Falcons", 0))
    }

    @Test
    fun `one sealed item is singular`() {
        assertEquals("Falcons · 1 notification is still hidden", SessionEndCopy.text("Falcons", 1))
    }

    @Test
    fun `many sealed items are plural`() {
        assertEquals("Falcons · 7 notifications are still hidden", SessionEndCopy.text("Falcons", 7))
    }
}
