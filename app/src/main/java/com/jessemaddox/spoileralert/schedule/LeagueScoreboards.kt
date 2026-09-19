package com.jessemaddox.spoileralert.schedule

/** Catalog league id mapped to ESPN's public scoreboard path and optional endpoint query. */
internal data class LeagueScoreboard(
    val leagueId: String,
    val path: String,
    val extraQuery: String? = null,
    val displayName: String,
    /** PGA/F1 events have no home/away teams but are still protectable named events. */
    val allowTeamless: Boolean = false,
    /** Minimum retained event window for feeds with unreliable or early end timestamps. */
    val minimumDurationMillis: Long = 0L,
)

/**
 * Every league shown in the event browser. The first seven are the core feeds; the remaining
 * feeds are the intentionally tucked-away long tail. All use the same bounded GET-only path.
 */
internal val LEAGUE_SCOREBOARDS = listOf(
    LeagueScoreboard("nfl", "football/nfl", displayName = "NFL"),
    LeagueScoreboard(
        "cfb", "football/college-football", extraQuery = "groups=80",
        displayName = "College Football",
    ),
    LeagueScoreboard("nba", "basketball/nba", displayName = "NBA"),
    LeagueScoreboard("mlb", "baseball/mlb", displayName = "MLB"),
    LeagueScoreboard("epl", "soccer/eng.1", displayName = "Premier League"),
    LeagueScoreboard("mls", "soccer/usa.1", displayName = "MLS"),
    LeagueScoreboard("soccer", "soccer/fifa.world", displayName = "World Cup"),
    LeagueScoreboard("nhl", "hockey/nhl", displayName = "NHL"),
    LeagueScoreboard("wnba", "basketball/wnba", displayName = "WNBA"),
    LeagueScoreboard("tgl", "golf/tgl", displayName = "TGL"),
    LeagueScoreboard(
        "golf", "golf/pga", displayName = "PGA Tour", allowTeamless = true,
        minimumDurationMillis = 4L * 24 * 60 * 60 * 1000,
    ),
    LeagueScoreboard(
        "f1", "racing/f1", displayName = "Formula 1", allowTeamless = true,
    ),
)

/** Feeds that can earn immediate Home placement even without a conventional saved-team game. */
internal val HOME_PRIORITY_LEAGUE_IDS = listOf("golf", "soccer", "nfl")
