package com.jessemaddox.spoileralert.domain

/** Makes rolling chat titles stable without looking at hidden message content. */
object ConversationIdentity {
    private val rollingCount =
        Regex("""\s*\((\d+)\s+(?:new\s+)?messages?\)(?:\s*:\s*.*)?\s*$""", RegexOption.IGNORE_CASE)

    fun canonical(label: String): String = label.replace(rollingCount, "").trim()

    /** Some messaging apps expose the cumulative count only in the rolling conversation title. */
    fun rollingMessageCount(label: String?): Int? = label
        ?.let { rollingCount.find(it)?.groupValues?.getOrNull(1) }
        ?.toIntOrNull()
}
