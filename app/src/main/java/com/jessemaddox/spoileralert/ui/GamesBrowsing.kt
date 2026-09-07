package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.GameWindows
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure selection/grouping for the Big games section and the Games browser. */
object GamesBrowsing {
    fun emptyStateMessage(
        hasScheduleData: Boolean,
        hasSearchOrSportFilter: Boolean,
        todayOnly: Boolean,
    ): String = when {
        !hasScheduleData ->
            "Events are still loading, or no schedule is available yet. Try again in a moment."
        hasSearchOrSportFilter ->
            "No events match these filters. Clear the search or choose All sports."
        todayOnly ->
            "No events found for today. Try Upcoming to browse ahead."
        else -> "No upcoming events found."
    }

    fun happensOn(game: LeagueGameEntity, day: LocalDate, zoneId: ZoneId): Boolean {
        val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = game.endMillis ?: (game.startMillis + GameWindows.IN_PROGRESS_LOOKBACK_MS)
        return game.startMillis < dayEnd && end >= dayStart
    }

    /**
     * Discovery list: upcoming league games the user does NOT already cover via a shield's
     * cached games (dedupe by eventId against the per-shield `games` table). Pool order
     * (soonest first) is preserved.
     */
    fun discover(
        leagueGames: List<LeagueGameEntity>,
        coveredEventIds: Set<String>,
        limit: Int,
    ): List<LeagueGameEntity> =
        leagueGames.asSequence().filter { it.eventId !in coveredEventIds }.take(limit).toList()

    /** Browser sections: games bucketed by local calendar day, start order preserved. */
    fun groupByDay(
        games: List<LeagueGameEntity>,
        zone: ZoneId,
    ): List<Pair<LocalDate, List<LeagueGameEntity>>> =
        games.groupBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
            .toList()
            .sortedBy { it.first }
}
