package com.jessemaddox.spoileralert.schedule

import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.schedule.PregamePrompt.PromptDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PregamePromptTest {

    private val now = 1_760_000_000_000L

    private fun shield(
        armed: Boolean = false,
        pregamePrompts: Boolean = true,
        kind: String = "TEAM",
    ) = ShieldEntity(
        id = 7, name = "Atlanta Falcons", aliasesJson = "[]", kind = kind,
        catalogTeamId = "nfl-atl", armed = armed, pregamePrompts = pregamePrompts,
    )

    private fun game(startMillis: Long, completed: Boolean = false) = GameEntity(
        id = "fiction-pregame", shieldId = 7, name = "Cedar Comets at Harbor Kites",
        shortName = "CED @ HAR", startMillis = startMillis, completed = completed,
        fetchedAtMillis = now,
    )

    @Test
    fun `future start prompts with pregame copy`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), game(now + 10 * 60_000L), now)
        assertEquals(PromptDecision.Prompt(alreadyStarted = false), d)
    }

    @Test
    fun `global toggle off skips`() {
        val d = PregamePrompt.shouldPrompt(false, shield(), game(now + 10 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `armed shield skips - never double-prompt or arm silently`() {
        val d = PregamePrompt.shouldPrompt(true, shield(armed = true), game(now + 10 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `per-shield toggle off skips`() {
        val d = PregamePrompt.shouldPrompt(true, shield(pregamePrompts = false), game(now + 10 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `deleted shield skips`() {
        val d = PregamePrompt.shouldPrompt(true, null, game(now + 10 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `custom shield skips - only shields with schedules prompt`() {
        val d = PregamePrompt.shouldPrompt(true, shield(kind = "CUSTOM"), game(now + 10 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `disarmed GAME shield prompts - protect-later matchups get their pregame ask`() {
        val d = PregamePrompt.shouldPrompt(true, shield(kind = "GAME"), game(now + 10 * 60_000L), now)
        assertEquals(PromptDecision.Prompt(alreadyStarted = false), d)
    }

    @Test
    fun `armed GAME shield still skips`() {
        val d = PregamePrompt.shouldPrompt(true, shield(kind = "GAME", armed = true), game(now + 10 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `missing game skips`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), null, now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `completed game skips`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), game(now - 60_000L, completed = true), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `started 29 minutes ago prompts with arm-now copy`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), game(now - 29 * 60_000L), now)
        assertEquals(PromptDecision.Prompt(alreadyStarted = true), d)
    }

    @Test
    fun `started 31 minutes ago skips - too late to matter`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), game(now - 31 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `kickoff at exactly now counts as started`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), game(now), now)
        assertEquals(PromptDecision.Prompt(alreadyStarted = true), d)
    }

    @Test
    fun `start 2 hours in the future skips - postponed later, fresh prompt lands at refresh`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), game(now + 2 * 60 * 60_000L), now)
        assertTrue(d is PromptDecision.Skip)
    }

    @Test
    fun `start just inside the early tolerance still prompts`() {
        val d = PregamePrompt.shouldPrompt(true, shield(), game(now + 15 * 60_000L - 1L), now)
        assertEquals(PromptDecision.Prompt(alreadyStarted = false), d)
    }
}
