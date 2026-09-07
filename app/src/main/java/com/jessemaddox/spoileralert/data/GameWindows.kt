package com.jessemaddox.spoileralert.data

/**
 * Shared "in-progress" look-back window (ship-blocker fix, collated review finding 1): a game
 * that kicked off up to this long ago still counts as protectable/current everywhere — Big
 * games, the Games browser, a shield's pending-game card, the widget's "current game", and an
 * armed session's matchup name ([com.jessemaddox.spoileralert.ui.MatchupNames.IN_PROGRESS_MS]
 * is an alias for this same constant). Before this fix each surface picked its own cutoff (or,
 * for the two DAO queries this feeds, no look-back at all), so a game past kickoff dropped out
 * of the Big games list / browser / pending-game card even though [ScheduleRefreshWorker]
 * deliberately keeps its league_games row alive for hours afterward.
 *
 * One constant so every surface agrees on what "still live" means. 4h covers soccer's
 * realistic max runtime (90 + extra time + penalties + broadcast/stoppage slop) without
 * keeping day-old blowouts around — narrower than [ScheduleRefreshWorker]'s 6h row-retention
 * grace, which only needs to outlive this display window, not match it exactly.
 */
object GameWindows {
    const val IN_PROGRESS_LOOKBACK_MS = 4L * 60 * 60 * 1000

    /** Cutoff to pass as a DAO's "since"/"now" bound so upcoming-games queries also surface
     *  games that started within the look-back window. */
    fun sinceMillis(nowMillis: Long): Long = nowMillis - IN_PROGRESS_LOOKBACK_MS

    /** Has this game's kickoff already passed (as of [nowMillis])? Metadata-only check — the
     *  "Started" tag on Big games / browser rows uses this, never any score/status detail. */
    fun hasStarted(startMillis: Long, nowMillis: Long): Boolean = startMillis <= nowMillis
}
