package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EventIdentityTest {
    @Test fun `canonicalizes both Open shield names`() {
        assertEquals(
            EventIdentity.canonical("The Open"),
            EventIdentity.canonical("The Open Championship"),
        )
        assertEquals("The Open", EventIdentity.canonical("The Open Championship").displayName)
        assertEquals(true, EventIdentity.canonical("The Open").mergeAdjacentDates)
    }

    @Test fun `known World Cup identity supplies soccer presentation metadata`() {
        val worldCup = EventIdentity.canonical("FIFA World Cup")
        assertEquals("soccer", worldCup.sportId)
        assertEquals(true, worldCup.mergeAdjacentDates)
    }

    @Test fun `revealed World Cup content overrides stale Open attribution`() {
        val open = EventMatchCandidate(1, "The Open", listOf("british open"))
        val worldCup = EventMatchCandidate(2, "FIFA World Cup", listOf("world cup"))
        val match = EventIdentity.bestMatch(
            "England takes third place at the World Cup", listOf(open, worldCup),
        )
        assertEquals(2L, match?.id)
    }

    @Test fun `does not treat substrings as event mentions`() {
        val open = EventMatchCandidate(1, "The Open", emptyList())
        assertEquals(null, EventIdentity.bestMatch("The opening ceremony", listOf(open)))
    }
}
