package com.jessemaddox.spoileralert.data

import com.jessemaddox.spoileralert.domain.Alias
import com.jessemaddox.spoileralert.domain.MatchMode
import com.jessemaddox.spoileralert.domain.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldCodecTest {
    @Test fun `aliases round-trip through json`() {
        val aliases = listOf(Alias("falcons"), Alias("atl", short = true))
        assertEquals(aliases, ShieldCodec.decodeAliases(ShieldCodec.encodeAliases(aliases)))
    }

    @Test fun `armed entity converts to ArmedShield`() {
        val e = ShieldEntity(id = 7, name = "Falcons",
            aliasesJson = ShieldCodec.encodeAliases(listOf(Alias("falcons"))), kind = "TEAM",
            armed = true, armedAtMillis = 1L)
        val armed = ShieldCodec.toArmedShield(e)
        assertEquals(7L, armed.id)
        assertTrue(Alias("falcons") in armed.aliases)
        assertTrue(Alias("scoreless") in armed.aliases)
    }

    @Test fun `session deadline and protection expiry include the fixed grace window`() {
        val armed = ShieldEntity(id = 1, name = "X", aliasesJson = "[]", kind = "TEAM",
            armed = true, armedAtMillis = 1_000L, autoDisarmHours = 2)
        val sessionDeadline = 1_000L + 2 * 60 * 60 * 1_000L
        assertEquals(sessionDeadline, ShieldCodec.sessionDeadlineMillis(armed))
        assertEquals(sessionDeadline + ShieldCodec.GRACE_MS, ShieldCodec.expiresAtMillis(armed))
        val unarmed = armed.copy(armed = false, armedAtMillis = null)
        assertEquals(null, ShieldCodec.sessionDeadlineMillis(unarmed))
        assertEquals(null, ShieldCodec.expiresAtMillis(unarmed))
    }

    @Test fun `matchesPatterns is enabled for sports shields only`() {
        fun armed(kind: String) = ShieldCodec.toArmedShield(
            ShieldEntity(id = 1, name = "X", aliasesJson = "[]", kind = kind)
        )
        assertTrue(armed("TEAM").matchesPatterns)
        assertTrue(armed("GAME").matchesPatterns)
        assertFalse(armed("CUSTOM").matchesPatterns)
        assertFalse(armed("FANTASY").matchesPatterns)
    }

    @Test fun `decode tolerates unknown future fields`() {
        val decoded = ShieldCodec.decodeAliases("""[{"text":"falcons","futureField":42}]""")
        assertEquals(listOf(Alias("falcons")), decoded)
    }

    @Test fun `custom shields expand inflections at decode time, team shields do not`() {
        val custom = ShieldEntity(id = 1, name = "test",
            aliasesJson = ShieldCodec.encodeAliases(listOf(Alias("test"))), kind = "CUSTOM")
        assertEquals(listOf(Alias("test"), Alias("tests")), ShieldCodec.toArmedShield(custom).aliases)

        val team = custom.copy(kind = "TEAM")
        assertTrue(Alias("test") in ShieldCodec.toArmedShield(team).aliases)
    }

    @Test fun `sports shields gain conservative game state phrases without changing stored aliases`() {
        val stored = listOf(Alias("atlanta united"), Alias("atl", short = true))
        val team = ShieldEntity(id = 1, name = "Atlanta United",
            aliasesJson = ShieldCodec.encodeAliases(stored), kind = "TEAM")
        val custom = team.copy(kind = "CUSTOM")

        assertTrue(Alias("scoreless") in ShieldCodec.toArmedShield(team).aliases)
        assertTrue(Alias("goalless") in ShieldCodec.toArmedShield(team).aliases)
        assertTrue(Alias("what a finish") in ShieldCodec.toArmedShield(team).aliases)
        assertTrue(Alias("game over", short = true) in ShieldCodec.toArmedShield(team).aliases)
        assertFalse(Alias("scoreless") in ShieldCodec.toArmedShield(custom).aliases)
        assertFalse(Alias("what a finish") in ShieldCodec.toArmedShield(custom).aliases)
        assertFalse(Alias("game over", short = true) in ShieldCodec.toArmedShield(custom).aliases)
        assertEquals(stored, ShieldCodec.decodeAliases(team.aliasesJson))
        val armed = ShieldCodec.toArmedShield(team)
        assertEquals(
            armed,
            Matcher.match(
                "Was not expecting a scoreless first half.", listOf(armed), MatchMode.STRICT,
            ),
        )
    }
}
