package com.jessemaddox.spoileralert.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CatchUpQuestion { SUMMARY, GOAL_TIMES, RESUME }

/** Only user-selected public-game parameters, never notification data. */
data class CatchUpRequest(
    val question: CatchUpQuestion,
    val viewingMinute: String = "",
    val leadInMinutes: Int = 5,
    val easternTime: Boolean = false,
)

/** Only the requested answer text leaves this reducer. No team allocations or descriptions. */
internal object SoccerGoalGuide {
    fun answer(timeline: ScoreTimeline?, request: CatchUpRequest): String {
        val verified = timeline?.takeIf { it.leagueId in setOf("epl", "mls", "soccer") }?.let(::validatedTimeline)
            ?: return "I can't verify the full goal history yet. Try again shortly."
        val latest = verified.points.last().soccerClock
            ?: return "I can't verify the latest match clock yet."
        val point = if (request.viewingMinute.isBlank()) null else SoccerClock.input(request.viewingMinute)
            ?: return "Enter your game clock, such as 10, 63:20, or 45+2. At a half boundary, add the half: 2h 45:20."
        if (point != null && point.position > latest.position) return "Your viewing point is ahead of the latest checked match clock. Try again shortly."
        if (point != null && point.position < verified.fromPosition) return "I can't verify the goal history at your viewing point yet."
        val scope = "Through ${latest.label}${if (timeline!!.completed) " (match finished)" else ""}."
        val goals = verified.points.filter { it.scoring && it.soccerClock != null }
        if (request.question == CatchUpQuestion.GOAL_TIMES) {
            val selected = goals.filter { point == null || it.latestMillis >= point.position * 1_000L }
            if (selected.isEmpty()) return "No goals recorded${point?.let { " from ${it.label}" }.orEmpty()}. $scope"
            return buildString {
                append(if (point == null) "Goal minutes" else "Goal minutes from ${point.label}")
                append("\n")
                append(selected.joinToString("\n") { goal ->
                    val clock = goal.soccerClock!!
                    val time = if (request.easternTime) goal.wallclockMillis?.let { " · approx. ${eastern(it)}" }
                        ?: " · Eastern time unavailable" else ""
                    "${clock.label}$time"
                })
                append("\n$scope")
            }
        }
        if (point == null) return "Enter where you are in the match to get a viewing cue. $scope"
        val next = goals.firstOrNull { it.latestMillis >= point.position * 1_000L }
            ?: return "No further goals recorded from ${point.label}. $scope This checks goals only."
        val resume = next.soccerClock!!.leadIn(request.leadInMinutes)
        if (resume.position <= point.position) return "Keep watching from ${point.label}. You're already within the lead-in. $scope"
        val wallclock = if (request.easternTime) next.wallclockMillis?.let {
            " Approx. ${eastern(it - (next.soccerClock.position - resume.position) * 1_000L)}; broadcast timing may differ."
        } ?: " Eastern time unavailable." else ""
        return "Resume at ${resume.label} for the buildup.$wallclock\nFrom your ${point.label} viewing point. $scope Goals only."
    }

    private fun eastern(millis: Long): String = DateTimeFormatter.ofPattern("h:mm a z", Locale.US)
        .withZone(ZoneId.of("America/New_York")).format(Instant.ofEpochMilli(millis))
}
