package com.jessemaddox.spoileralert.domain

import kotlinx.serialization.Serializable

/** One keyword a shield matches on. `short` aliases (e.g. "ATL") are skipped in STRICT mode. */
@Serializable
data class Alias(val text: String, val short: Boolean = false)

/** A shield as the matcher sees it: only armed shields are ever passed in. */
data class ArmedShield(
    val id: Long,
    val name: String,
    val aliases: List<Alias>,
    /** When true, hiding a notification also posts a content-free stand-in alert. */
    val maskedAlerts: Boolean = false,
    /** Sports shields (TEAM/GAME) also match bare score/margin patterns — see [ScorePatterns]. */
    val matchesPatterns: Boolean = false,
)

enum class MatchMode { AGGRESSIVE, STRICT, OFF }
