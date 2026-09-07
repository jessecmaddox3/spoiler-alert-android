package com.jessemaddox.spoileralert.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.max

data class EventTiming(
    val schedule: String,
    val relative: String,
)

data class SportPresentation(
    val label: String,
    val glyph: String,
)

/** Local, deterministic presentation of public schedule metadata. */
object EventPresentation {
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * MINUTE_MS

    fun sport(leagueId: String?): SportPresentation = when (leagueId) {
        "nfl", "cfb" -> SportPresentation("Football", "🏈")
        "nba", "wnba" -> SportPresentation("Basketball", "🏀")
        "mlb" -> SportPresentation("Baseball", "⚾")
        "nhl" -> SportPresentation("Hockey", "🏒")
        "golf", "tgl" -> SportPresentation("Golf", "⛳")
        "f1" -> SportPresentation("Formula 1", "🏎")
        "epl", "mls", "soccer", "fifa.world" -> SportPresentation("Soccer", "⚽")
        else -> SportPresentation("Sport", "★")
    }

    fun sportForEventKey(eventKey: String): SportPresentation? = when {
        eventKey.startsWith("golf:") -> sport("golf")
        eventKey.startsWith("soccer:") -> sport("soccer")
        eventKey.startsWith("football:") -> sport("nfl")
        eventKey.startsWith("basketball:") -> sport("nba")
        eventKey.startsWith("baseball:") -> sport("mlb")
        eventKey.startsWith("hockey:") -> sport("nhl")
        eventKey.startsWith("racing:") -> sport("f1")
        else -> null
    }

    fun timing(
        startMillis: Long,
        endMillis: Long?,
        leagueId: String?,
        nowMillis: Long,
        zoneId: ZoneId,
    ): EventTiming {
        val effectiveEnd = endMillis ?: (
            startMillis + SessionDurations.typicalGameLengthMinutes(leagueId) * MINUTE_MS
        )
        val multiDay = effectiveEnd - startMillis >= DAY_MS
        val schedule = if (multiDay) {
            val date = DateTimeFormatter.ofPattern("MMM d")
            val start = Instant.ofEpochMilli(startMillis).atZone(zoneId)
            val end = Instant.ofEpochMilli(effectiveEnd).atZone(zoneId)
            "${date.format(start)}–${date.format(end)}"
        } else {
            timeRange(startMillis, effectiveEnd, zoneId)
        }
        val relative = when {
            nowMillis < startMillis -> "Starts in ${friendlyDuration(startMillis - nowMillis)}"
            nowMillis <= effectiveEnd && multiDay -> "Happening now"
            nowMillis <= effectiveEnd -> "Started ${friendlyDuration(nowMillis - startMillis)} ago"
            else -> "Scheduled event window has ended"
        }
        return EventTiming(schedule, relative)
    }

    fun friendlyDuration(millis: Long): String {
        val minutes = max(0L, millis / MINUTE_MS)
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        return when {
            days > 0 && hours > 0 -> "${days}d ${hours}h"
            days > 0 -> "${days}d"
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "less than a minute"
        }
    }

    private fun timeRange(startMillis: Long, endMillis: Long, zoneId: ZoneId): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val start = Instant.ofEpochMilli(startMillis).atZone(zoneId)
        val end = Instant.ofEpochMilli(endMillis).atZone(zoneId)
        return "${formatter.format(start)}–${formatter.format(end)}"
    }
}
