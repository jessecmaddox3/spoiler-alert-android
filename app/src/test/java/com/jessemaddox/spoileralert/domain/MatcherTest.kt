package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatcherTest {
    private val falcons = ArmedShield(
        id = 1, name = "Atlanta Falcons",
        aliases = listOf(Alias("falcons"), Alias("dirty birds"), Alias("atl", short = true)),
    )
    private val masters = ArmedShield(
        id = 2, name = "The Masters",
        aliases = listOf(Alias("masters"), Alias("augusta"), Alias("scheffler")),
    )
    private val chiefs = ArmedShield(
        id = 3, name = "Kansas City Chiefs",
        aliases = listOf(Alias("chiefs"), Alias("kc", short = true)),
    )
    private val shields = listOf(falcons, masters)

    @Test fun `matches case-insensitively on word boundary`() {
        assertEquals(falcons, Matcher.match("FALCONS WIN 24-17!", shields, MatchMode.AGGRESSIVE))
    }

    @Test fun `does not match inside another word`() {
        assertNull(Matcher.match("The atlas of world maps", shields, MatchMode.AGGRESSIVE))
        assertNull(Matcher.match("remastersed edition", shields, MatchMode.AGGRESSIVE))
    }

    @Test fun `matches multi-word alias`() {
        assertEquals(falcons, Matcher.match("the dirty birds pulled it off", shields, MatchMode.AGGRESSIVE))
    }

    @Test fun `short alias matches in aggressive mode`() {
        assertEquals(falcons, Matcher.match("ATL up by 10 at the half", shields, MatchMode.AGGRESSIVE))
    }

    @Test fun `short alias ignored in strict mode`() {
        assertNull(Matcher.match("landing at ATL at 9pm", shields, MatchMode.STRICT))
    }

    @Test fun `short alias with nearby sports context matches in strict messaging mode`() {
        assertEquals(
            falcons,
            Matcher.match("Also, are y'all catching the ATL game?", shields, MatchMode.STRICT),
        )
        assertNull(Matcher.match("ATL flight then a game tomorrow", shields, MatchMode.STRICT))
    }

    @Test fun `context-first short alias needs a colon or dash join in strict mode`() {
        // "final ATL" (space only, context first) is an innocent travel phrase, not a spoiler.
        assertNull(Matcher.match("the final ATL flight leaves at 10pm", shields, MatchMode.STRICT))
        // A colon/dash after the context word is the disambiguator that restores it.
        assertEquals(chiefs, Matcher.match("match: KC tonight", listOf(chiefs), MatchMode.STRICT))
        assertEquals(falcons, Matcher.match("final: ATL 24", shields, MatchMode.STRICT))
    }

    @Test fun `full alias still matches in strict mode`() {
        assertEquals(falcons, Matcher.match("did you see the Falcons game??", shields, MatchMode.STRICT))
    }

    @Test fun `off mode never matches`() {
        assertNull(Matcher.match("FALCONS WIN", shields, MatchMode.OFF))
    }

    @Test fun `punctuation and emoji count as word boundaries`() {
        assertEquals(masters, Matcher.match("Scheffler🔥 -10 thru 14", shields, MatchMode.AGGRESSIVE))
        assertEquals(falcons, Matcher.match("(Falcons) final", shields, MatchMode.AGGRESSIVE))
    }

    @Test fun `no shields means no match`() {
        assertNull(Matcher.match("FALCONS WIN", emptyList(), MatchMode.AGGRESSIVE))
    }

    @Test fun `first matching shield in list order wins`() {
        val text = "Falcons fans at Augusta"
        assertEquals(falcons, Matcher.match(text, listOf(falcons, masters), MatchMode.AGGRESSIVE))
        assertEquals(masters, Matcher.match(text, listOf(masters, falcons), MatchMode.AGGRESSIVE))
    }

    @Test fun `multi-word alias matches across double spaces and newlines`() {
        assertEquals(falcons, Matcher.match("dirty  birds win", shields, MatchMode.AGGRESSIVE))
        assertEquals(falcons, Matcher.match("dirty\nbirds win", shields, MatchMode.AGGRESSIVE))
    }

    @Test fun `multi-word alias still requires word boundaries at its ends`() {
        assertNull(Matcher.match("thedirty birds", shields, MatchMode.AGGRESSIVE))
        assertNull(Matcher.match("dirty birdsong", shields, MatchMode.AGGRESSIVE))
    }

    @Test fun `non-ascii aliases fold case`() {
        val hulk = ArmedShield(id = 3, name = "Hülkenberg", aliases = listOf(Alias("hülkenberg")))
        assertEquals(hulk, Matcher.match("HÜLKENBERG WINS", listOf(hulk), MatchMode.AGGRESSIVE))
    }

    @Test fun `score pattern attributes to the first pattern-enabled shield`() {
        val a = ArmedShield(id = 10, name = "A", aliases = emptyList(), matchesPatterns = true)
        val b = ArmedShield(id = 11, name = "B", aliases = emptyList(), matchesPatterns = true)
        assertEquals(a, Matcher.match("Final: 24-17", listOf(a, b), MatchMode.AGGRESSIVE))
        assertEquals(b, Matcher.match("Final: 24-17", listOf(b, a), MatchMode.AGGRESSIVE))
    }

    @Test fun `score pattern ignored when no shield enables patterns`() {
        val plain = ArmedShield(id = 12, name = "Plain", aliases = listOf(Alias("falcons")))
        assertNull(Matcher.match("up by 10", listOf(plain), MatchMode.AGGRESSIVE))
    }

    @Test fun `strict mode excludes tier B bare score patterns`() {
        val s = ArmedShield(id = 13, name = "S", aliases = emptyList(), matchesPatterns = true)
        assertNull(Matcher.match("21-14", listOf(s), MatchMode.STRICT))
        assertEquals(s, Matcher.match("21-14", listOf(s), MatchMode.AGGRESSIVE))
        assertEquals(s, Matcher.match("final 21-14", listOf(s), MatchMode.STRICT))
    }
}
