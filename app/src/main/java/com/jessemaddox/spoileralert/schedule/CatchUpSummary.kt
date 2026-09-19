package com.jessemaddox.spoileralert.schedule

/** Deterministic text assembled from one public event response. No AI or play descriptions. */
internal object CatchUpSummary {
    fun answer(snapshot: ScoreSnapshot, timeline: ScoreTimeline?, request: CatchUpRequest): String {
        val facts = mutableListOf("Latest checked game state")
        if (snapshot.leagueId == "golf") {
            facts += when {
                snapshot.completed -> "The tournament is over."
                snapshot.statusState == "pre" -> "The tournament has not started yet."
                snapshot.currentRound != null -> "Round ${snapshot.currentRound}."
                else -> "Round information is not available yet."
            }
        } else {
            facts += SpoilerFreeScore.progress(snapshot) ?: "Detailed progress is not available yet."
        }
        if (snapshot.leagueId in setOf("epl", "mls", "soccer")) {
            facts += SpoilerFreeScore.detail(snapshot, ScoreQuestion.TOTAL_GOALS)
                ?: "The current goal total is not available yet."
            if (request.viewingMinute.isNotBlank()) facts += SoccerGoalGuide.answer(timeline, request)
        }
        return facts.joinToString("\n")
    }
}
