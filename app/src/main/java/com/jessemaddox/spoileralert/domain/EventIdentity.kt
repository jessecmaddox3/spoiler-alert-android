package com.jessemaddox.spoileralert.domain

data class CanonicalEvent(
    val key: String,
    val displayName: String,
    val sportId: String? = null,
    val mergeAdjacentDates: Boolean = false,
)

data class EventMatchCandidate(
    val id: Long,
    val name: String,
    val aliases: List<String>,
)

/** Stable local identity for event names that have appeared under multiple shield types. */
object EventIdentity {
    fun canonical(name: String): CanonicalEvent {
        val normalized = normalize(name)
        return when (normalized) {
            "the open", "the open championship", "british open" ->
                CanonicalEvent(
                    key = "golf:the-open",
                    displayName = "The Open",
                    sportId = "golf",
                    mergeAdjacentDates = true,
                )
            "fifa world cup", "world cup" ->
                CanonicalEvent(
                    key = "soccer:world-cup",
                    displayName = "FIFA World Cup",
                    sportId = "soccer",
                    mergeAdjacentDates = true,
                )
            else -> CanonicalEvent("name:$normalized", name)
        }
    }

    /** Best explicit event/team mention in already-revealed local content. */
    fun bestMatch(
        text: String,
        candidates: List<EventMatchCandidate>,
    ): EventMatchCandidate? = candidates.mapNotNull { candidate ->
        val longest = (listOf(candidate.name) + candidate.aliases)
            .asSequence()
            .map(::normalize)
            .filter { it.length >= 4 && containsPhrase(text, it) }
            .maxOfOrNull { it.length }
            ?: return@mapNotNull null
        candidate to longest
    }.maxWithOrNull(compareBy<Pair<EventMatchCandidate, Int>> { it.second }
        .thenBy { it.first.name.length })?.first

    private fun containsPhrase(rawText: String, normalizedPhrase: String): Boolean {
        val normalizedText = normalize(rawText)
        return Regex(
            "(?<![\\p{L}\\p{N}])${Regex.escape(normalizedPhrase)}(?![\\p{L}\\p{N}])"
        ).containsMatchIn(normalizedText)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
