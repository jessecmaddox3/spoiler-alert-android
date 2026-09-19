package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FantasyAppSuggestionsTest {

    private data class Row(val pkg: String, val label: String)

    @Test fun `known fantasy packages bubble to the top`() {
        val apps = listOf(
            Row("com.whatsapp", "WhatsApp"),
            Row("com.sleeperbot", "Sleeper"),
            Row("com.android.chrome", "Chrome"),
        )
        val sorted = FantasyAppSuggestions.sorted(apps, { it.pkg }, { it.label })
        assertEquals("com.sleeperbot", sorted.first().pkg)
    }

    @Test fun `everything else stays alphabetical by label`() {
        val apps = listOf(
            Row("com.whatsapp", "WhatsApp"),
            Row("com.android.chrome", "Chrome"),
        )
        val sorted = FantasyAppSuggestions.sorted(apps, { it.pkg }, { it.label })
        assertEquals(listOf("Chrome", "WhatsApp"), sorted.map { it.label })
    }

    @Test fun `multiple known packages sort alphabetically among themselves`() {
        val apps = listOf(
            Row("com.sleeperbot", "Sleeper"),
            Row("com.espn.fantasy.lm.football", "ESPN Fantasy"),
        )
        val sorted = FantasyAppSuggestions.sorted(apps, { it.pkg }, { it.label })
        assertEquals(listOf("ESPN Fantasy", "Sleeper"), sorted.map { it.label })
    }

    @Test fun `an unmatched app sorts alphabetically like anything else`() {
        val apps = listOf(Row("com.some.unknown.app", "Zebra App"), Row("com.other", "Apple App"))
        val sorted = FantasyAppSuggestions.sorted(apps, { it.pkg }, { it.label })
        assertEquals(listOf("Apple App", "Zebra App"), sorted.map { it.label })
    }
}
