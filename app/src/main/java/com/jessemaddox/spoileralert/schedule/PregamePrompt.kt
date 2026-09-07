package com.jessemaddox.spoileralert.schedule

import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity

/**
 * Fire-time eligibility for a pregame prompt. Pure logic, no Android deps —
 * the worker gathers state and this decides. Consent-based by design: a prompt
 * only ever ASKS; nothing here (or downstream) arms a shield silently.
 */
object PregamePrompt {
    /** Prompt this long before kickoff. */
    const val LEAD_TIME_MS = 10L * 60 * 1000

    /** A game that already kicked off still gets a prompt for this long ("arm now?"). */
    const val STARTED_GRACE_MS = 30L * 60 * 1000

    /**
     * Slop allowed beyond LEAD_TIME_MS for a start that's still further out than expected.
     * Defense-in-depth against a stale enqueue surviving a postponement: if the (re-read)
     * start is further in the future than this, the enqueue predates a reschedule and a
     * fresh one will land at the next refresh, so this fire is a no-op skip.
     */
    private const val EARLY_TOLERANCE_MS = 5 * 60 * 1000L

    sealed interface PromptDecision {
        /** Show the prompt. [alreadyStarted] switches the copy to "started at … — arm now?". */
        data class Prompt(val alreadyStarted: Boolean) : PromptDecision
        data class Skip(val reason: String) : PromptDecision
    }

    fun shouldPrompt(
        globalEnabled: Boolean,
        shield: ShieldEntity?,
        game: GameEntity?,
        nowMillis: Long,
    ): PromptDecision {
        if (!globalEnabled) return PromptDecision.Skip("pregame prompts disabled globally")
        if (shield == null) return PromptDecision.Skip("shield deleted")
        // TEAM shields and GAME (matchup) shields both carry cached games; a disarmed
        // "Protect later" GAME shield gets its arm-now ask exactly like a team does.
        if (shield.kind != "TEAM" && shield.kind != "GAME")
            return PromptDecision.Skip("shield kind has no schedule")
        if (!shield.pregamePrompts) return PromptDecision.Skip("pregame prompts off for this shield")
        if (shield.armed) return PromptDecision.Skip("shield already armed")
        if (game == null) return PromptDecision.Skip("game no longer in schedule cache")
        if (game.completed) return PromptDecision.Skip("game already completed")
        val sinceStart = nowMillis - game.startMillis
        return when {
            sinceStart < -(LEAD_TIME_MS + EARLY_TOLERANCE_MS) ->
                PromptDecision.Skip("game was rescheduled later; a fresh prompt will be enqueued at refresh")
            sinceStart < 0 -> PromptDecision.Prompt(alreadyStarted = false)
            sinceStart < STARTED_GRACE_MS -> PromptDecision.Prompt(alreadyStarted = true)
            else -> PromptDecision.Skip("game started over 30 minutes ago")
        }
    }
}
