package com.jessemaddox.spoileralert.data

/** Public event identifiers and timing only. No notification or vault content crosses this API. */
data class EventCompletionTarget(
    val shieldId: Long,
    val shieldName: String,
    val leagueId: String,
    val eventId: String,
    val startMillis: Long,
    val expectedEndMillis: Long,
    val protectionDeadlineMillis: Long,
)
