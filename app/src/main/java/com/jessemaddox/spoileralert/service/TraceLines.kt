package com.jessemaddox.spoileralert.service

import com.jessemaddox.spoileralert.domain.MatchMode

/** Builds every trace line the interceptor records. Parameters are metadata only — no builder here
 * accepts notification text, so a persisted trace line cannot carry private message content. */
object TraceLines {

    fun safetySkip(pkg: String, category: String?): String =
        "$pkg: skipped — safety category $category"

    fun fantasyHide(pkg: String): String =
        "$pkg: hidden (fantasy app, session active)"

    fun emptyCache(pkg: String): String =
        "$pkg: skipped — armed cache empty"

    fun modeOff(pkg: String): String =
        "$pkg: skipped — mode OFF (excluded or system)"

    fun capabilities(
        pkg: String,
        conversation: Boolean,
        priority: Boolean,
        messages: Int,
        open: Boolean,
        reply: Boolean,
        markUnread: Boolean,
    ): String =
        "$pkg: capabilities conversation=$conversation priority=$priority messages=$messages " +
            "open=$open reply=$reply markUnread=$markUnread"

    fun noMatch(pkg: String, mode: MatchMode, shieldCount: Int): String =
        "$pkg mode=$mode shields=$shieldCount: no match"

    fun matched(pkg: String, mode: MatchMode, name: String): String =
        "$pkg mode=$mode: MATCHED '$name'"
}
