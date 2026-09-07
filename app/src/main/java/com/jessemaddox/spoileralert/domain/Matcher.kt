package com.jessemaddox.spoileralert.domain

object Matcher {

    /** Returns the first armed shield whose aliases hit the text, or null. */
    fun match(text: String, shields: List<ArmedShield>, mode: MatchMode): ArmedShield? {
        if (mode == MatchMode.OFF || shields.isEmpty() || text.isBlank()) return null
        return shields.firstOrNull { shield ->
            shield.aliases.any { alias ->
                when {
                    mode == MatchMode.AGGRESSIVE || !alias.short -> containsWord(text, alias.text)
                    else -> containsContextualShortAlias(text, alias.text)
                }
            } || (shield.matchesPatterns && ScorePatterns.matches(text, mode))
        }
    }

    /**
     * Strict messaging mode normally skips ambiguous abbreviations such as ATL. Restore them
     * only when directly paired with unmistakable sports language ("ATL game", "match: KC").
     * This catches conversational setup without turning "ATL flight" into a spoiler match.
     */
    internal fun containsContextualShortAlias(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        val alias = needle.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        val context = "(?:game|match|score|scored|goal|kickoff|tipoff|inning|quarter|half|halftime|overtime|playoffs?|final)"
        // Alias-first ("ATL game") keeps a plain-space join. Context-first is riskier ("final ATL
        // flight" is innocent), so it requires an explicit colon/dash ("final: ATL", "match: KC").
        val aliasJoin = "(?:['’]s)?[\\s:–—-]+"
        val contextJoin = "(?:['’]s)?\\s*[:–—-]+\\s*"
        return Regex(
            "(?<![\\p{L}\\p{N}])(?:$alias$aliasJoin$context|$context$contextJoin$alias)(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(haystack)
    }

    // \p{L}\p{N} boundaries so punctuation, emoji, and whitespace all delimit words.
    internal fun containsWord(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        val body = needle.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}])" + body + "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
        return pattern.containsMatchIn(haystack)
    }
}
