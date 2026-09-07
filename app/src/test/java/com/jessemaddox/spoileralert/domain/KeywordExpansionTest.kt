package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeywordExpansionTest {

    private fun texts(aliases: List<Alias>) = aliases.map { it.text }

    @Test fun `singular keyword gains plural variant`() {
        assertEquals(listOf("test", "tests"), texts(KeywordExpansion.expand(Alias("test"))))
    }

    @Test fun `sibilant endings gain es variant`() {
        assertEquals(listOf("match", "matches"), texts(KeywordExpansion.expand(Alias("match"))))
        assertEquals(listOf("sox", "soxes"), texts(KeywordExpansion.expand(Alias("sox"))))
    }

    @Test fun `consonant-y becomes ies`() {
        assertEquals(listOf("derby", "derbies"), texts(KeywordExpansion.expand(Alias("derby"))))
    }

    @Test fun `words already ending in s are left alone`() {
        assertEquals(listOf("tests"), texts(KeywordExpansion.expand(Alias("tests"))))
        assertEquals(listOf("masters"), texts(KeywordExpansion.expand(Alias("masters"))))
    }

    @Test fun `multi-word keyword inflects the last word only`() {
        assertEquals(listOf("world cup", "world cups"), texts(KeywordExpansion.expand(Alias("world cup"))))
    }

    @Test fun `short flag is preserved on variants`() {
        val expanded = KeywordExpansion.expand(Alias("wc", short = true))
        assertEquals(true, expanded.all { it.short })
    }

    @Test fun `f to ves irregular plural`() {
        assertEquals(listOf("wolf", "wolves"), texts(KeywordExpansion.expand(Alias("wolf"))))
        assertEquals(listOf("life", "lives"), texts(KeywordExpansion.expand(Alias("life"))))
        assertEquals(listOf("leaf", "leaves"), texts(KeywordExpansion.expand(Alias("leaf"))))
        assertEquals(listOf("half", "halves"), texts(KeywordExpansion.expand(Alias("half"))))
        assertEquals(listOf("calf", "calves"), texts(KeywordExpansion.expand(Alias("calf"))))
    }

    @Test fun `fe to ves irregular plural fallback rule`() {
        assertEquals(listOf("knife", "knives"), texts(KeywordExpansion.expand(Alias("knife"))))
        assertEquals(listOf("wife", "wives"), texts(KeywordExpansion.expand(Alias("wife"))))
    }

    @Test fun `mapped irregular plurals`() {
        assertEquals(listOf("man", "men"), texts(KeywordExpansion.expand(Alias("man"))))
        assertEquals(listOf("woman", "women"), texts(KeywordExpansion.expand(Alias("woman"))))
        assertEquals(listOf("foot", "feet"), texts(KeywordExpansion.expand(Alias("foot"))))
        assertEquals(listOf("tooth", "teeth"), texts(KeywordExpansion.expand(Alias("tooth"))))
        assertEquals(listOf("goose", "geese"), texts(KeywordExpansion.expand(Alias("goose"))))
        assertEquals(listOf("child", "children"), texts(KeywordExpansion.expand(Alias("child"))))
        assertEquals(listOf("person", "people"), texts(KeywordExpansion.expand(Alias("person"))))
        assertEquals(listOf("mouse", "mice"), texts(KeywordExpansion.expand(Alias("mouse"))))
    }

    @Test fun `already-irregular-plural keyword is left as-is`() {
        assertEquals(listOf("wolves"), texts(KeywordExpansion.expand(Alias("wolves"))))
        assertEquals(listOf("men"), texts(KeywordExpansion.expand(Alias("men"))))
        assertEquals(listOf("children"), texts(KeywordExpansion.expand(Alias("children"))))
    }

    @Test fun `regular f and fe endings use s instead of an invalid ves form`() {
        assertEquals(listOf("chief", "chiefs"), texts(KeywordExpansion.expand(Alias("chief"))))
        assertEquals(listOf("roof", "roofs"), texts(KeywordExpansion.expand(Alias("roof"))))
        assertEquals(listOf("safe", "safes"), texts(KeywordExpansion.expand(Alias("safe"))))
    }

    @Test fun `multi-word alias inflects last word with irregular rule`() {
        assertEquals(listOf("big wolf", "big wolves"), texts(KeywordExpansion.expand(Alias("big wolf"))))
    }

    // Regression case from a manual test:
    // custom shield keyword "test", brother texted "the tests won".
    @Test fun `regression - keyword test matches the tests won via expansion`() {
        val raw = listOf(Alias("test"))
        val expanded = raw.flatMap { KeywordExpansion.expand(it) }.distinct()
        val shield = ArmedShield(id = 1, name = "test", aliases = expanded)
        assertNotNull(
            Matcher.match("I can't believe the tests won 20 to 0. You're such a goober.",
                listOf(shield), MatchMode.STRICT)
        )
        // and without expansion it genuinely fails (documents the original bug)
        val unexpanded = ArmedShield(id = 1, name = "test", aliases = raw)
        assertNull(
            Matcher.match("I can't believe the tests won 20 to 0.", listOf(unexpanded), MatchMode.STRICT)
        )
    }
}
