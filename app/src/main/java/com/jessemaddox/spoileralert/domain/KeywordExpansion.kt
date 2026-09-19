package com.jessemaddox.spoileralert.domain

/**
 * Expands a user-typed custom keyword into its simple English inflections so
 * "test" also matches "tests". Applied to CUSTOM shields only — curated team
 * aliases are authored in the exact forms they should match.
 */
object KeywordExpansion {

    fun expand(alias: Alias): List<Alias> {
        val words = alias.text.split(' ')
        val last = words.last()
        val variantLast = pluralOf(last) ?: return listOf(alias)
        val variant = (words.dropLast(1) + variantLast).joinToString(" ")
        return listOf(alias, Alias(variant, short = alias.short))
    }

    /** Irregular plurals that don't follow the regular suffix rules below. */
    private val irregulars: Map<String, String> = mapOf(
        "wolf" to "wolves",
        "life" to "lives",
        "leaf" to "leaves",
        "half" to "halves",
        "calf" to "calves",
        "knife" to "knives",
        "wife" to "wives",
        "man" to "men",
        "woman" to "women",
        "foot" to "feet",
        "tooth" to "teeth",
        "goose" to "geese",
        "child" to "children",
        "person" to "people",
        "mouse" to "mice",
    )

    /** Already-plural irregular forms; a keyword typed this way should not be re-expanded. */
    private val irregularPlurals: Set<String> = irregulars.values.toSet()

    private fun pluralOf(word: String): String? = when {
        word.length < 2 || word.endsWith("s") || word in irregularPlurals -> null
        word in irregulars -> irregulars.getValue(word)
        word.endsWith("x") || word.endsWith("z") ||
            word.endsWith("ch") || word.endsWith("sh") -> word + "es"
        word.endsWith("y") && word.length > 2 && word[word.length - 2] !in "aeiou" ->
            word.dropLast(1) + "ies"
        else -> word + "s"
    }
}
