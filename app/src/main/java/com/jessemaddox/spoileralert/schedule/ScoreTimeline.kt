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
)

internal data class ScoreTimeline(
    val leagueId: String,
    val completed: Boolean,
    val points: List<ScoreTimelinePoint>,
)

/**
 * Converts a private score timeline into fixed answers. Only [ScoreCheckpoint], [ScoreAnswer],
 * and [SkipAnswer] cross into UI state.
 */
internal object SpoilerFreeTimeline {
    fun checkpoints(timeline: ScoreTimeline): List<ScoreCheckpoint> {
        val points = timeline.points.sortedBy { it.position }
        val latest = points.lastOrNull() ?: return emptyList()
        val candidates = when (timeline.leagueId) {
            "nfl", "cfb" -> timedCheckpoints(timeline.leagueId, 4, 15 * 60, listOf(10, 5, 2), latest.position)
            "nba" -> timedCheckpoints(timeline.leagueId, 4, 12 * 60, listOf(10, 5, 2), latest.position)
            "wnba" -> timedCheckpoints(timeline.leagueId, 4, 10 * 60, listOf(5, 2), latest.position)
            "nhl" -> timedCheckpoints(timeline.leagueId, 3, 20 * 60, listOf(15, 10, 5, 2), latest.position)
            "epl", "mls", "soccer" -> soccerCheckpoints(latest.position)
            "mlb" -> baseballCheckpoints(points)
            else -> emptyList()
        }.toMutableList()

        if (candidates.none { it.position == latest.position }) {
            candidates += ScoreCheckpoint(
                id = "latest:${latest.position}",
                label = if (timeline.completed) "End of game" else "Live point · ${pointLabel(timeline, latest)}",
                position = latest.position,
            )
        } else if (timeline.completed) {
            val index = candidates.indexOfLast { it.position == latest.position }
            if (index >= 0) candidates[index] = candidates[index].copy(label = "End of game")
        }
        return candidates
            .filter { it.position <= latest.position }
            .distinctBy { it.position }
            .sortedBy { it.position }
    }

    fun canSkip(
        timeline: ScoreTimeline,
        start: ScoreCheckpoint,
        end: ScoreCheckpoint,
    ): SkipAnswer {
        if (end.position <= start.position) return SkipAnswer.UNAVAILABLE
        val ordered = timeline.points.sortedBy { it.position }
        if (ordered.isEmpty() || end.position > ordered.last().position) return SkipAnswer.UNAVAILABLE

        var previous = ordered.lastOrNull { it.position <= start.position }
        for (point in ordered) {
            if (point.position <= start.position) continue
            if (point.position > end.position) break
            val scoreIncreased = previous?.let { before ->
                val oldTotal = before.homeScore?.plus(before.awayScore ?: return@let false)
                val newTotal = point.homeScore?.plus(point.awayScore ?: return@let false)
                oldTotal != null && newTotal != null && newTotal > oldTotal
            } ?: false
            if (point.scoring || scoreIncreased) return SkipAnswer.DO_NOT_SKIP
            previous = point
        }
        return SkipAnswer.SAFE
    }

    fun answerAt(
        timeline: ScoreTimeline,
        checkpoint: ScoreCheckpoint,
        question: ScoreQuestion,
    ): ScoreAnswer {
        val ordered = timeline.points.sortedBy { it.position }
        if (ordered.isEmpty() || checkpoint.position > ordered.last().position) return ScoreAnswer.UNAVAILABLE
        val point = ordered.lastOrNull { it.position <= checkpoint.position }
            ?: ScoreTimelinePoint(0, 1, null, null, 0, 0, false)
        val isEnd = timeline.completed && checkpoint.position >= ordered.last().position
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

    private fun soccerCheckpoints(latestPosition: Int): List<ScoreCheckpoint> {
        val result = mutableListOf(ScoreCheckpoint("minute:0", "Start of match", 0))
        var minute = 15
        while (minute * 60 <= latestPosition) {
            val label = when (minute) {
                45 -> "Halftime"
                90 -> "End of regulation"
                else -> "${ordinal(minute)} minute"
            }
            result += ScoreCheckpoint("minute:$minute", label, minute * 60)
            minute += 15
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
            "${ordinal(point.position / 60)} minute"
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
