package com.jessemaddox.spoileralert.service

import com.jessemaddox.spoileralert.domain.MatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TraceLinesTest {

    @Test
    fun `safety skip names the category`() {
        assertEquals(
            "com.foo: skipped — safety category call",
            TraceLines.safetySkip("com.foo", "call"),
        )
    }

    @Test
    fun `safety skip tolerates a null category`() {
        assertEquals(
            "com.foo: skipped — safety category null",
            TraceLines.safetySkip("com.foo", null),
        )
    }

    @Test
    fun `fantasy hide is fixed`() {
        assertEquals(
            "com.foo: hidden (fantasy app, session active)",
            TraceLines.fantasyHide("com.foo"),
        )
    }

    @Test
    fun `empty cache is fixed`() {
        assertEquals(
            "com.foo: skipped — armed cache empty",
            TraceLines.emptyCache("com.foo"),
        )
    }

    @Test
    fun `mode off is fixed`() {
        assertEquals(
            "com.foo: skipped — mode OFF (excluded or system)",
            TraceLines.modeOff("com.foo"),
        )
    }

    @Test
    fun `capabilities pins every flag`() {
        assertEquals(
            "com.foo: capabilities conversation=true priority=false messages=3 " +
                "open=true reply=false markUnread=true",
            TraceLines.capabilities(
                pkg = "com.foo",
                conversation = true,
                priority = false,
                messages = 3,
                open = true,
                reply = false,
                markUnread = true,
            ),
        )
    }

    @Test
    fun `no match is redacted with no preview`() {
        assertEquals(
            "com.foo mode=STRICT shields=2: no match",
            TraceLines.noMatch("com.foo", MatchMode.STRICT, 2),
        )
    }

    @Test
    fun `match names the shield`() {
        assertEquals(
            "com.foo mode=AGGRESSIVE: MATCHED 'Braves'",
            TraceLines.matched("com.foo", MatchMode.AGGRESSIVE, "Braves"),
        )
    }

    @Test
    fun `no match line cannot carry notification content`() {
        val secret = "Braves win 5-3!"
        val line = TraceLines.noMatch("com.foo", MatchMode.STRICT, 1)
        assertFalse(line.contains(secret))
        assertFalse(line.contains("5-3"))
    }
}
