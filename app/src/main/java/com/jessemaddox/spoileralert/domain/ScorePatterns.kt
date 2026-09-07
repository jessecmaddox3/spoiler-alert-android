package com.jessemaddox.spoileralert.domain

/**
 * Numeric score/margin patterns that leak a result even with no team name present ("up by 10",
 * "final 24-17", "21-14"). Added at runtime only to TEAM/GAME shields (via [ArmedShield.matchesPatterns]),
 * so they participate only while a sports shield is armed.
 *
 * Tier A carries enough context ("by", a "final/ft/score" cue) to run in both AGGRESSIVE and STRICT.
 * Tier B is bare ("up 7", "21-14") and runs in AGGRESSIVE only, where a couple of documented false
 * positives (a date-like "12-25", "up 10") are an accepted trade for catching terse score chatter.
 * Digits are capped at two, so long ids ("118-110") never match — a documented gap.
 *
 * The dash class is en-dash/em-dash/hyphen ONLY — never a colon or slash — so times ("7:30") and
 * dates ("7/22") are never read as scores.
 */
object ScorePatterns {
    private const val NUM =
        "(?:\\d{1,2}|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
            "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty)"

    // Tier A — enough context to be safe in both modes.
    private val A1 = Regex(
        "(?<![\\p{L}])(?:up|down|ahead|behind|lead|leads|leading|led|trail|trails|trailing|" +
            "won|win|winning|lost|lose|losing|beat|beating|scored?)\\s+by\\s+$NUM(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val A2 = Regex(
        "(?<![\\p{L}\\p{N}])(?:final|halftime|ht|ft|score|leads?|won|winning)\\b[\\s:]+" +
            "\\d{1,2}\\s*[–—-]\\s*\\d{1,2}(?![\\d])",
        RegexOption.IGNORE_CASE,
    )
    private val A3 = Regex(
        "(?<![\\d:/])\\d{1,2}\\s*[–—-]\\s*\\d{1,2}\\s+(?:final|ft)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )

    // Tier B — bare, AGGRESSIVE only.
    private val B1 = Regex(
        "(?<![\\p{L}])(?:up|down)\\s+$NUM(?![\\p{L}\\p{N}])(?!\\s*(?:lbs|kg|%|percent|pounds|degrees))",
        RegexOption.IGNORE_CASE,
    )
    private val B2 = Regex(
        "(?<![\\d:/–—-])\\d{1,2}\\s*[–—-]\\s*\\d{1,2}(?![\\d:/–—-])",
        RegexOption.IGNORE_CASE,
    )

    private val tierA = listOf(A1, A2, A3)
    private val tierB = listOf(B1, B2)

    fun matches(text: String, mode: MatchMode): Boolean {
        if (mode == MatchMode.OFF || text.isBlank()) return false
        if (tierA.any { it.containsMatchIn(text) }) return true
        return mode == MatchMode.AGGRESSIVE && tierB.any { it.containsMatchIn(text) }
    }
}
