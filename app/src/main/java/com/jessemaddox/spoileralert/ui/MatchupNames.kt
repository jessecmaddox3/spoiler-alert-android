package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.GameWindows
import kotlin.math.abs

/**
 * Matchup-named sessions (v3 amendment item 2): when a session lines up with a cached game,
 * surfaces name the session by the matchup ("PROTECTED · BUF @ KC") instead of the shield.
 * Pure logic; callers fall back to the shield name on null. GAME shields are already named
 * by their matchup, so these mostly upgrade TEAM-shield sessions.
 */
object MatchupNames {
    /** A game that kicked off up to this long before arming still "owns" the session. Alias
     *  for [GameWindows.IN_PROGRESS_LOOKBACK_MS] — one shared constant (collated review
     *  finding 1) so every "is this game still current" check agrees. */
    const val IN_PROGRESS_MS = GameWindows.IN_PROGRESS_LOOKBACK_MS

    /** Pre-arm sheet: how far ahead a kickoff still names the sheet title. */
    const val SHEET_LOOKAHEAD_MS = 12L * 60 * 60 * 1000

    /**
     * Matchup shortName for an ARMED session: the not-completed cached game whose kickoff
     * falls inside [armedAt - IN_PROGRESS_MS, expiresAt], nearest kickoff to arm time first.
     */
    fun sessionMatchup(games: List<GameEntity>, armedAtMillis: Long, expiresAtMillis: Long): String? =
        games.filter { !it.completed && it.startMillis in (armedAtMillis - IN_PROGRESS_MS)..expiresAtMillis }
            .minByOrNull { abs(it.startMillis - armedAtMillis) }
            ?.shortName

    /**
     * Matchup shortName for the PRE-ARM session sheet: a not-completed cached game that
     * recently kicked off or kicks off within the lookahead window.
     */
    fun sheetMatchup(games: List<GameEntity>, nowMillis: Long): String? =
        games.filter { !it.completed && it.startMillis in (nowMillis - IN_PROGRESS_MS)..(nowMillis + SHEET_LOOKAHEAD_MS) }
            .minByOrNull { abs(it.startMillis - nowMillis) }
            ?.shortName
}
