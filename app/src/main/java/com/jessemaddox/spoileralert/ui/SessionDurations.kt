package com.jessemaddox.spoileralert.ui

import java.time.Duration
import java.time.LocalDateTime

/** Pure duration math for protection sessions (session sheet + countdown chips). */
object SessionDurations {
    /** Session cap, also used by "Until I turn it off" (check-ins still fire). */
    const val MAX_HOURS = 72
    const val DEFAULT_HOURS = 4

    /**
     * Whole hours from [now] until the next 23:00 local, rounded up, min 1.
     * Backs the "Until tonight" chip's picker preset.
     */
    fun hoursUntilTonight(now: LocalDateTime): Int {
        var target = now.toLocalDate().atTime(23, 0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return hoursUntil(now, target)
    }

    /** Whole hours from [now] to a picked end time, rounded up; min 1, capped at [MAX_HOURS]. */
    fun hoursUntil(now: LocalDateTime, target: LocalDateTime): Int {
        val minutes = Duration.between(now, target).toMinutes()
        return ((minutes + 59) / 60).toInt().coerceIn(1, MAX_HOURS)
    }

    /**
     * Sport-aware suggested session length by catalog league id: a game plus postgame
     * coverage. Football runs long (5h), baseball middles (4h), basketball/soccer are
     * quick (3h); anything unknown gets the honest 4h default.
     */
    fun suggestedHours(leagueId: String?): Int = when (leagueId) {
        "nfl", "cfb" -> 5
        "mlb" -> 4
        "nba", "epl", "mls", "soccer" -> 3
        else -> DEFAULT_HOURS
    }

    /** Post-game buffer folded into a game-aware session so a session covers ceremonies,
     *  the final whistle, and immediate reaction coverage — not just the clock. */
    const val POSTGAME_BUFFER_MINUTES = 45L

    /** Only default-cover a game the user is arming AROUND kickoff: a game further out than
     *  this uses the flat [suggestedHours] instead (arming hours early shouldn't hide for
     *  10+ hours by default). In-progress and just-about-to-start games qualify. */
    const val NEAR_WINDOW_MINUTES = 6 * 60L

    /** Typical wall-clock length of a game in the given league, in minutes (broadcast start
     *  to final whistle, before the post-game buffer). Football and baseball run long,
     *  basketball middles, league soccer is tight, knockout/World Cup soccer runs longer. */
    fun typicalGameLengthMinutes(leagueId: String?): Long = when (leagueId) {
        "nfl", "cfb" -> 210L        // ~3.5h
        "mlb" -> 210L               // ~3.5h
        "nba" -> 150L               // ~2.5h
        "epl", "mls" -> 120L        // ~2h
        "soccer", "fifa.world" -> 150L // ~2.5h (knockouts run long)
        else -> 180L                // ~3h honest default
    }

    /**
     * Epoch-millis end of a game the session should cover (kickoff + typical length + buffer),
     * or null when there's nothing sensible to cover — no known game, the game already ended,
     * or kickoff is further out than [NEAR_WINDOW_MINUTES]. Callers that get null fall back to
     * the flat [suggestedHours]. Shared by [gameAwareDefaultHours] and the sheet's "covers the
     * game" affordance so the label and the math never disagree.
     */
    fun coverableGameEndMillis(leagueId: String?, gameStartMillis: Long?, nowMillis: Long): Long? {
        if (gameStartMillis == null) return null
        val end = gameStartMillis +
            (typicalGameLengthMinutes(leagueId) + POSTGAME_BUFFER_MINUTES) * 60_000L
        if (end <= nowMillis) return null // already over — nothing left to cover
        if (gameStartMillis > nowMillis + NEAR_WINDOW_MINUTES * 60_000L) return null // too far out
        return end
    }

    /**
     * Game-aware default session length (v3 amendment item 8, sport-aware check-in timing):
     * when a game start is known and imminent/in-progress, default the session to END at the
     * game's expected finish (kickoff + [typicalGameLengthMinutes] + [POSTGAME_BUFFER_MINUTES]),
     * expressed as whole hours from [nowMillis], min 1, capped at [MAX_HOURS]. The check-in
     * (15 min before expiry) then lands near expected game-end automatically. Falls back to the
     * flat [suggestedHours] when there's no coverable game (see [coverableGameEndMillis]).
     */
    fun gameAwareDefaultHours(leagueId: String?, gameStartMillis: Long?, nowMillis: Long): Int {
        val end = coverableGameEndMillis(leagueId, gameStartMillis, nowMillis)
            ?: return suggestedHours(leagueId)
        val minutes = (end - nowMillis + 59_999L) / 60_000L // ceil to whole minutes
        return ((minutes + 59) / 60).toInt().coerceIn(1, MAX_HOURS)
    }

    /** "2h 40m" / "4h" / "12m" / "<1m" for live countdown chips. */
    fun formatRemaining(millis: Long): String {
        if (millis < 60_000L) return "<1m"
        val totalMinutes = millis / 60_000L
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
