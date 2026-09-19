package com.jessemaddox.spoileralert.schedule

/** A neutral point that is safe for UI state. It contains no score or event description. */
data class ScoreCheckpoint(
    val id: String,
    val label: String,
    val position: Int,
)

enum class SkipAnswer { SAFE, DO_NOT_SKIP, UNAVAILABLE }

internal enum class InningHalf { TOP, BOTTOM }

/** Raw public play state. It is held only inside the schedule layer and is never persisted. */
internal data class ScoreTimelinePoint(
    val position: Int,
    val period: Int,
    val clockSecondsRemaining: Int?,
    val inningHalf: InningHalf?,
    val homeScore: Int?,
    val awayScore: Int?,
    val scoring: Boolean,
    val soccerClock: SoccerClock? = null,
    val wallclockMillis: Long? = null,
    /** Exact event time, or a bounded interval when the provider supplies only a minute. */
    val earliestMillis: Long = position * 1_000L,
    val latestMillis: Long = earliestMillis,
    /** A verified final header belongs after every play, even when its clock has minute precision. */
    val terminal: Boolean = false,
)

internal data class ScoreTimeline(
    val leagueId: String,
    val completed: Boolean,
    val points: List<ScoreTimelinePoint>,
    val complete: Boolean,
)

/** Shared trust boundary for every historical answer, including viewing cues. */
internal data class ValidatedTimeline(val points: List<ScoreTimelinePoint>, val completed: Boolean) {
    val fromPosition get() = ((points.first().latestMillis + 999) / 1_000).toInt()
    val throughPosition get() = (if (completed) (points.last().latestMillis + 999) / 1_000
        else points.last().earliestMillis / 1_000).toInt()
}

internal fun validatedTimeline(timeline: ScoreTimeline): ValidatedTimeline? {
    if (!timeline.complete || timeline.points.isEmpty()) return null
    val soccer = timeline.leagueId in setOf("epl", "mls", "soccer")
    // Stable sorting preserves the provider's ordering for multiple events at one clock value.
    if (timeline.points.count { it.terminal } > 1 || (!timeline.completed && timeline.points.any { it.terminal })) return null
    val points = timeline.points.sortedWith(compareBy<ScoreTimelinePoint> {
        if (it.terminal) it.latestMillis else it.earliestMillis
    }.thenBy { it.terminal })
    if (points.any { point ->
        point.homeScore == null || point.awayScore == null || point.homeScore < 0 ||
            point.awayScore < 0 || point.position < 0 || point.earliestMillis < 0 ||
            point.earliestMillis / 1_000 != point.position.toLong() || point.latestMillis < point.earliestMillis ||
            point.latestMillis - point.earliestMillis > (if (soccer) 59_999 else 1) ||
            !validTimelinePosition(timeline.leagueId, point)
    }) return null
    if (points.first().homeScore != 0 || points.first().awayScore != 0) return null
    if (points.zipWithNext().any { (before, after) ->
        after.homeScore!! < before.homeScore!! || after.awayScore!! < before.awayScore!! ||
            ((after.homeScore > before.homeScore || after.awayScore > before.awayScore) && !after.scoring)
    }) return null
    if (soccer && points.first().soccerClock != SoccerClock(1, 0)) return null
    if (points.any { it.terminal } && !points.last().terminal) return null
    if (timeline.completed) {
        val last = points.last()
        val validEnd = if (soccer) {
            last.period in setOf(2, 4) && last.soccerClock!!.minute == SoccerClock.endMinute(last.period)
        } else when (timeline.leagueId) {
            "nfl", "cfb", "nba", "wnba" -> last.period >= 4 && last.clockSecondsRemaining == 0
            "nhl" -> last.period == 3 && last.clockSecondsRemaining == 0
            else -> false // No verified baseball final-coverage contract yet.
        }
        if (!validEnd) return null
    }
    return ValidatedTimeline(points, timeline.completed)
}

private fun validTimelinePosition(league: String, point: ScoreTimelinePoint): Boolean {
    if (point.period !in 1..100) return false
    if (league in setOf("epl", "mls", "soccer")) {
        val clock = point.soccerClock ?: return false
        if (clock.period !in 1..4 || clock.period != point.period || clock.second !in 0..59 ||
            clock.added !in 0..30 || clock.minute !in SoccerClock.startMinute(clock.period)..SoccerClock.endMinute(clock.period) ||
            (clock.added > 0 && clock.minute != SoccerClock.endMinute(clock.period))) return false
        return point.position == clock.position
    }
    if (league == "mlb") {
        val half = point.inningHalf ?: return false
        val start = ((point.period - 1L) * 2 + if (half == InningHalf.BOTTOM) 1 else 0) *
            SpoilerFreeTimeline.BASEBALL_HALF_POSITION
        return point.position.toLong() in start until start + SpoilerFreeTimeline.BASEBALL_HALF_POSITION
    }
    val regulationPeriods = if (league == "nhl") 3 else 4
    val regulationDuration = when (league) {
        "nfl", "cfb" -> 900
        "nba" -> 720
        "wnba" -> 600
        "nhl" -> 1200
        else -> return false
    }
    val duration = if (point.period <= regulationPeriods) regulationDuration else {
        if (league !in setOf("nba", "wnba")) return false
        300
    }
    val remaining = point.clockSecondsRemaining ?: return false
    if (remaining !in 0..duration) return false
    val start = if (point.period <= regulationPeriods) (point.period - 1L) * duration else
        regulationPeriods.toLong() * regulationDuration + (point.period - regulationPeriods - 1L) * duration
    return point.position.toLong() == start + duration - remaining
}

/**
 * Converts a private score timeline into fixed answers. Only [ScoreCheckpoint], [ScoreAnswer],
 * and [SkipAnswer] cross into UI state.
 */
internal object SpoilerFreeTimeline {
    fun checkpoints(timeline: ScoreTimeline): List<ScoreCheckpoint> {
        val verified = validatedTimeline(timeline) ?: return emptyList()
        val points = verified.points
        val latest = points.last()
        val candidates = when (timeline.leagueId) {
            "nfl", "cfb" -> timedCheckpoints(timeline.leagueId, 4, 15 * 60, listOf(10, 5, 2), latest.position)
            "nba" -> timedCheckpoints(timeline.leagueId, 4, 12 * 60, listOf(10, 5, 2), latest.position)
            "wnba" -> timedCheckpoints(timeline.leagueId, 4, 10 * 60, listOf(5, 2), latest.position)
            "nhl" -> timedCheckpoints(timeline.leagueId, 3, 20 * 60, listOf(15, 10, 5, 2), latest.position)
            "epl", "mls", "soccer" -> soccerCheckpoints(latest)
            "mlb" -> baseballCheckpoints(points)
            else -> emptyList()
        }.toMutableList()

        val lastPosition = verified.throughPosition
        if (candidates.none { it.position == lastPosition }) {
            candidates += ScoreCheckpoint(
                id = "latest:$lastPosition",
                label = if (timeline.completed) "End of game" else "Latest checked point · ${pointLabel(timeline, latest)}",
                position = lastPosition,
            )
        } else if (timeline.completed) {
            val index = candidates.indexOfLast { it.position == lastPosition }
            if (index >= 0) candidates[index] = candidates[index].copy(label = "End of game")
        }
        return candidates
            .filter { it.position in verified.fromPosition..verified.throughPosition }
            .distinctBy { it.position }
            .sortedBy { it.position }
    }

    fun canSkip(
        timeline: ScoreTimeline,
        start: ScoreCheckpoint,
        end: ScoreCheckpoint,
    ): SkipAnswer {
        val verified = validatedTimeline(timeline) ?: return SkipAnswer.UNAVAILABLE
        if (start.position < verified.fromPosition || end.position <= start.position ||
            end.position > verified.throughPosition) return SkipAnswer.UNAVAILABLE
        val ordered = verified.points

        val startMillis = start.position * 1_000L
        val endMillis = end.position * 1_000L
        if (ordered.none { it.latestMillis <= startMillis }) return SkipAnswer.UNAVAILABLE
        for ((previous, point) in ordered.zipWithNext()) {
            val scoreIncreased = point.homeScore!! > previous.homeScore!! || point.awayScore!! > previous.awayScore!!
            if ((point.scoring || scoreIncreased) && point.latestMillis > startMillis &&
                point.earliestMillis <= endMillis) return SkipAnswer.DO_NOT_SKIP
        }
        return SkipAnswer.SAFE
    }

    fun answerAt(
        timeline: ScoreTimeline,
        checkpoint: ScoreCheckpoint,
        question: ScoreQuestion,
    ): ScoreAnswer {
        val verified = validatedTimeline(timeline) ?: return ScoreAnswer.UNAVAILABLE
        if (checkpoint.position !in verified.fromPosition..verified.throughPosition) return ScoreAnswer.UNAVAILABLE
        val ordered = verified.points
        val atMillis = checkpoint.position * 1_000L
        if (timeline.completed && question == ScoreQuestion.OVER &&
            atMillis >= ordered.last().earliestMillis && atMillis < ordered.last().latestMillis) {
            return ScoreAnswer.UNAVAILABLE
        }
        if (ordered.any { it.scoring && it.earliestMillis <= atMillis && it.latestMillis > atMillis }) {
            return ScoreAnswer.UNAVAILABLE
        }
        val point = ordered.lastOrNull { it.latestMillis <= atMillis }
            ?: return ScoreAnswer.UNAVAILABLE
        val isEnd = timeline.completed && checkpoint.position >= verified.throughPosition
        return SpoilerFreeScore.answer(
            ScoreSnapshot(
                leagueId = timeline.leagueId,
                statusState = if (isEnd) "post" else "in",
                completed = isEnd,
                homeScore = point.homeScore,
                awayScore = point.awayScore,
                displayClock = point.clockSecondsRemaining?.let(::clockLabel),
                period = point.period,
                statusDetail = point.inningHalf?.let { half ->
                    "${if (half == InningHalf.TOP) "Top" else "Bottom"} ${ordinal(point.period)}"
                },
            ),
            question,
        )
    }

    private fun timedCheckpoints(
        leagueId: String,
        regulationPeriods: Int,
        periodSeconds: Int,
        remainingMinuteMarks: List<Int>,
        latestPosition: Int,
    ): List<ScoreCheckpoint> {
        val result = mutableListOf<ScoreCheckpoint>()
        for (period in 1..regulationPeriods) {
            val start = (period - 1) * periodSeconds
            if (start > latestPosition) break
            val startLabel = when {
                period == 1 -> "Start of game"
                period == 3 && regulationPeriods == 4 -> "Halftime"
                leagueId == "nhl" -> "Start of the ${ordinal(period)} period"
                else -> "Start of the ${ordinal(period)} quarter"
            }
            result += ScoreCheckpoint("period:$period:start", startLabel, start)
            remainingMinuteMarks.forEach { remainingMinutes ->
                val elapsed = periodSeconds - remainingMinutes * 60
                if (elapsed <= 0) return@forEach
                val position = start + elapsed
                if (position <= latestPosition) {
                    val segment = if (leagueId == "nhl") "period" else "quarter"
                    result += ScoreCheckpoint(
                        "period:$period:remaining:${remainingMinutes * 60}",
                        "${remainingMinutes}:00 left in the ${ordinal(period)} $segment",
                        position,
                    )
                }
            }
        }
        return result
    }

    private fun soccerCheckpoints(latest: ScoreTimelinePoint): List<ScoreCheckpoint> {
        val result = mutableListOf<ScoreCheckpoint>()
        for (period in 1..latest.period.coerceIn(1, 4)) {
            for (minute in SoccerClock.startMinute(period)..SoccerClock.endMinute(period) step 5) {
                val clock = SoccerClock(period, minute)
                if (clock.position <= latest.position) {
                    result += ScoreCheckpoint("soccer:${clock.position}", clock.label, clock.position)
                }
            }
        }
        return result
    }

    private fun baseballCheckpoints(points: List<ScoreTimelinePoint>): List<ScoreCheckpoint> =
        points.mapNotNull { point ->
            val half = point.inningHalf ?: return@mapNotNull null
            val unit = (point.period - 1) * 2 + if (half == InningHalf.BOTTOM) 1 else 0
            ScoreCheckpoint(
                id = "inning:${point.period}:${half.name}",
                label = "Start of the ${half.name.lowercase()} of the ${ordinal(point.period)}",
                position = unit * BASEBALL_HALF_POSITION,
            )
        }.distinctBy { it.id }

    private fun pointLabel(timeline: ScoreTimeline, point: ScoreTimelinePoint): String = when {
        timeline.leagueId == "mlb" && point.inningHalf != null ->
            "${point.inningHalf.name.lowercase().replaceFirstChar(Char::uppercase)} ${ordinal(point.period)}"
        timeline.leagueId in setOf("epl", "mls", "soccer") ->
            point.soccerClock?.label ?: "Match position unavailable"
        point.clockSecondsRemaining != null -> {
            val segment = if (timeline.leagueId == "nhl") "period" else "quarter"
            "${clockLabel(point.clockSecondsRemaining)} left in the ${ordinal(point.period)} $segment"
        }
        else -> "Current game position"
    }

    private fun clockLabel(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun ordinal(value: Int): String {
        val suffix = if (value % 100 in 11..13) "th" else when (value % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$value$suffix"
    }

    internal const val BASEBALL_HALF_POSITION = 1_000_000
}
