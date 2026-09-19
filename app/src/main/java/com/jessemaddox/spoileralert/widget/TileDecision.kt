package com.jessemaddox.spoileralert.widget

import com.jessemaddox.spoileralert.ui.SessionDurations

/**
 * Pure decision logic for the idle Quick Settings tile tap (v3 amendment item 9): arm the
 * single obvious next thing to protect, or defer to the app when it's ambiguous. Takes only
 * public shield/game metadata — never vault content — matching the tile's safety rule that
 * an accidental tap must never expose spoilers.
 *
 * "Obvious" is deliberately count-based, not time-based: exactly one unarmed TEAM shield with
 * a next schedulable game is unambiguous — mirroring the spec's "their single team's next
 * game" — REGARDLESS of how far off that game is. Two or more such shields is ambiguous
 * ("multiple teams/games imminent") even when their games are days apart, because the tile
 * has no affordance to ask which one the user means; it opens the app instead.
 */
object TileDecision {
    /** One armable candidate: an unarmed TEAM shield with a next schedulable game. */
    data class Candidate(val shieldId: Long, val name: String, val leagueId: String?)

    sealed interface Action {
        /** Arm [shieldId] for the sport-suggested duration ([SessionDurations.suggestedHours]). */
        data class Arm(val shieldId: Long, val hours: Int) : Action

        /** Ambiguous or nothing schedulable — collapse the shade and let the user choose. */
        data object OpenApp : Action
    }

    /** Idle tap: arm the one obvious candidate, else open the app. */
    fun decide(candidates: List<Candidate>): Action {
        val only = candidates.singleOrNull() ?: return Action.OpenApp
        return Action.Arm(only.shieldId, SessionDurations.suggestedHours(only.leagueId))
    }

    /** Idle subtitle: the one candidate's name, else the generic app name. */
    fun subtitle(candidates: List<Candidate>): String =
        candidates.singleOrNull()?.name ?: "Spoiler Alert"
}
