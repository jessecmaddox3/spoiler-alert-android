package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diff decision behind [ShieldRepository.refreshGameShield] (collated review finding 2):
 *  a GAME shield's name/aliases/start snapshot must be re-derived at use time instead of
 *  staying pinned to whatever was true at protect time. */
class GameShieldRefreshTest {

    @Test
    fun `nothing changed - no diff`() {
        val d = GameShieldRefresh.diff(
            currentName = "ARG @ ESP", currentAliasesJson = "[{\"text\":\"argentina\"}]", currentStartMillis = 1_000L,
            freshName = "ARG @ ESP", freshAliasesJson = "[{\"text\":\"argentina\"}]", freshStartMillis = 1_000L,
        )
        assertFalse(d.changed)
    }

    @Test
    fun `name changed - diff carries the fresh name`() {
        val d = GameShieldRefresh.diff(
            currentName = "ARG @ ESP", currentAliasesJson = "[]", currentStartMillis = 1_000L,
            freshName = "Argentina @ Spain", freshAliasesJson = "[]", freshStartMillis = 1_000L,
        )
        assertTrue(d.changed)
        assertEquals("Argentina @ Spain", d.name)
    }

    @Test
    fun `aliases changed - diff carries the fresh aliases`() {
        val d = GameShieldRefresh.diff(
            currentName = "ARG @ ESP", currentAliasesJson = "[]", currentStartMillis = 1_000L,
            freshName = "ARG @ ESP", freshAliasesJson = "[{\"text\":\"la roja\"}]", freshStartMillis = 1_000L,
        )
        assertTrue(d.changed)
        assertEquals("[{\"text\":\"la roja\"}]", d.aliasesJson)
    }

    @Test
    fun `start time changed - diff carries the fresh start`() {
        val d = GameShieldRefresh.diff(
            currentName = "ARG @ ESP", currentAliasesJson = "[]", currentStartMillis = 1_000L,
            freshName = "ARG @ ESP", freshAliasesJson = "[]", freshStartMillis = 2_000L,
        )
        assertTrue(d.changed)
        assertEquals(2_000L, d.startMillis)
    }

    @Test
    fun `missing cached game row - always a diff so it gets provisioned`() {
        val d = GameShieldRefresh.diff(
            currentName = "ARG @ ESP", currentAliasesJson = "[]", currentStartMillis = null,
            freshName = "ARG @ ESP", freshAliasesJson = "[]", freshStartMillis = 1_000L,
        )
        assertTrue(d.changed)
    }
}
