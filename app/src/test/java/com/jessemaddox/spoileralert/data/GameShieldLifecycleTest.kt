package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session-scoped GAME shields: when they appear on Home and when a reveal spends them. */
class GameShieldLifecycleTest {

    // --- showOnHome ---

    @Test
    fun `team and custom shields always show`() {
        assertTrue(GameShieldLifecycle.showOnHome("TEAM", armed = false, hiddenCount = 0, totalVaultCount = 0, hasPendingGame = false))
        assertTrue(GameShieldLifecycle.showOnHome("CUSTOM", armed = false, hiddenCount = 0, totalVaultCount = 0, hasPendingGame = false))
    }

    @Test
    fun `fantasy sentinel shield never shows on Home`() {
        assertFalse(GameShieldLifecycle.showOnHome("FANTASY", armed = false, hiddenCount = 5, totalVaultCount = 5, hasPendingGame = false))
        assertFalse(GameShieldLifecycle.showOnHome("FANTASY", armed = true, hiddenCount = 0, totalVaultCount = 0, hasPendingGame = true))
    }

    @Test
    fun `armed game shield shows`() {
        assertTrue(GameShieldLifecycle.showOnHome("GAME", armed = true, hiddenCount = 0, totalVaultCount = 0, hasPendingGame = true))
    }

    @Test
    fun `sealed game shield shows - items still hidden need a reveal path`() {
        assertTrue(GameShieldLifecycle.showOnHome("GAME", armed = false, hiddenCount = 2, totalVaultCount = 2, hasPendingGame = false))
    }

    @Test
    fun `protect-later game shield shows - disarmed, unused, game still pending`() {
        assertTrue(GameShieldLifecycle.showOnHome("GAME", armed = false, hiddenCount = 0, totalVaultCount = 0, hasPendingGame = true))
    }

    @Test
    fun `spent game shield hides - session over, everything revealed`() {
        // Even if the matchup itself hasn't finished (revealed mid-game), the session is spent.
        assertFalse(GameShieldLifecycle.showOnHome("GAME", armed = false, hiddenCount = 0, totalVaultCount = 3, hasPendingGame = true))
        assertFalse(GameShieldLifecycle.showOnHome("GAME", armed = false, hiddenCount = 0, totalVaultCount = 3, hasPendingGame = false))
    }

    @Test
    fun `stale game shield hides - never used and its game is gone`() {
        assertFalse(GameShieldLifecycle.showOnHome("GAME", armed = false, hiddenCount = 0, totalVaultCount = 0, hasPendingGame = false))
    }

    // --- deleteNowAfterReveal (ShieldRepository.cleanupGameShield) ---

    @Test
    fun `delete right after reveal only when nothing remains to render`() {
        assertTrue(GameShieldLifecycle.deleteNowAfterReveal("GAME", armed = false, hiddenCount = 0, totalVaultCount = 0))
    }

    @Test
    fun `revealed rows keep the shield until history retention prunes them`() {
        assertFalse(GameShieldLifecycle.deleteNowAfterReveal("GAME", armed = false, hiddenCount = 0, totalVaultCount = 2))
    }

    @Test
    fun `never delete while armed or while items stay hidden`() {
        assertFalse(GameShieldLifecycle.deleteNowAfterReveal("GAME", armed = true, hiddenCount = 0, totalVaultCount = 0))
        assertFalse(GameShieldLifecycle.deleteNowAfterReveal("GAME", armed = false, hiddenCount = 1, totalVaultCount = 1))
    }

    @Test
    fun `never delete team or custom shields`() {
        assertFalse(GameShieldLifecycle.deleteNowAfterReveal("TEAM", armed = false, hiddenCount = 0, totalVaultCount = 0))
        assertFalse(GameShieldLifecycle.deleteNowAfterReveal("CUSTOM", armed = false, hiddenCount = 0, totalVaultCount = 0))
    }
}
