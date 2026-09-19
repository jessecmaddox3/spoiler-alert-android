package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fantasy-app shields (v3 amendment item 3): a package-level rule, not keyword matching.
 * While ANY protection session is active, every notification from a designated fantasy app
 * is hidden — regardless of content.
 */
class FantasyPolicyTest {

    private val yahoo = "com.yahoo.mobile.client.android.fantasyfootball"

    @Test fun `hides a designated fantasy app while a session is active`() {
        assertTrue(FantasyPolicy.shouldHide(yahoo, setOf(yahoo), emptySet(), anySessionActive = true))
    }

    @Test fun `does nothing when no session is active`() {
        assertFalse(FantasyPolicy.shouldHide(yahoo, setOf(yahoo), emptySet(), anySessionActive = false))
    }

    @Test fun `does nothing for apps not designated as fantasy`() {
        assertFalse(FantasyPolicy.shouldHide("com.whatsapp", setOf(yahoo), emptySet(), anySessionActive = true))
    }

    @Test fun `does nothing when the fantasy set is empty`() {
        assertFalse(FantasyPolicy.shouldHide(yahoo, emptySet(), emptySet(), anySessionActive = true))
    }

    @Test fun `an explicit exclusion always wins over the fantasy designation`() {
        assertFalse(FantasyPolicy.shouldHide(yahoo, setOf(yahoo), setOf(yahoo), anySessionActive = true))
    }

    @Test fun `exclusion of an unrelated package does not affect the fantasy app`() {
        assertTrue(FantasyPolicy.shouldHide(yahoo, setOf(yahoo), setOf("com.other"), anySessionActive = true))
    }
}
