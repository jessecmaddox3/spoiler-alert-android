package com.jessemaddox.spoileralert.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePolicyTest {
    @Test fun `messaging apps get strict mode`() {
        assertEquals(MatchMode.STRICT, SourcePolicy.modeFor("com.whatsapp", emptySet()))
        assertEquals(MatchMode.STRICT, SourcePolicy.modeFor("com.google.android.apps.messaging", emptySet()))
    }

    @Test fun `known sports apps get aggressive mode`() {
        assertEquals(MatchMode.AGGRESSIVE, SourcePolicy.modeFor("com.espn.score_center", emptySet()))
    }

    @Test fun `unknown apps default to aggressive`() {
        assertEquals(MatchMode.AGGRESSIVE, SourcePolicy.modeFor("com.random.app", emptySet()))
    }

    @Test fun `user-excluded apps are off`() {
        assertEquals(MatchMode.OFF, SourcePolicy.modeFor("com.whatsapp", setOf("com.whatsapp")))
    }

    @Test fun `system packages are off`() {
        assertEquals(MatchMode.OFF, SourcePolicy.modeFor("android", emptySet()))
        assertEquals(MatchMode.OFF, SourcePolicy.modeFor("com.android.systemui", emptySet()))
        assertEquals(MatchMode.OFF, SourcePolicy.modeFor("com.android.phone", emptySet()))
    }

    @Test fun `chrome is not system - web push spoilers arrive via the browser`() {
        assertEquals(MatchMode.AGGRESSIVE, SourcePolicy.modeFor("com.android.chrome", emptySet()))
    }

    @Test fun `snapchat gets strict mode`() {
        assertEquals(MatchMode.STRICT, SourcePolicy.modeFor("com.snapchat.android", emptySet()))
    }

    @Test fun `weather apps are off`() {
        for (pkg in SourcePolicy.WEATHER) {
            assertEquals(pkg, MatchMode.OFF, SourcePolicy.modeFor(pkg, emptySet()))
        }
    }
}
