package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoNotificationIdentityTest {
    private val identity = DemoNotificationIdentity(
        shieldId = 7L,
        sourcePackage = "com.jessemaddox.spoileralert",
        title = "ESPN",
        text = "Falcons win a thriller",
        postedAfterMillis = 1_000L,
    )

    private fun row(
        shieldId: Long = 7L,
        sourcePackage: String = "com.jessemaddox.spoileralert",
        title: String = "ESPN",
        text: String = "Falcons win a thriller",
        postedAt: Long = 1_001L,
    ) = VaultEntity(
        id = 1L,
        shieldId = shieldId,
        sourcePackage = sourcePackage,
        sourceAppLabel = "Spoiler Alert",
        title = title,
        text = text,
        postedAtMillis = postedAt,
        notificationKey = "demo",
    )

    @Test fun `matches only the self-posted fake for this run`() {
        assertTrue(identity.matches(row()))
        assertFalse(identity.matches(row(sourcePackage = "com.espn.score_center")))
        assertFalse(identity.matches(row(text = "Falcons trade their quarterback")))
        assertFalse(identity.matches(row(postedAt = 999L)))
        assertFalse(identity.matches(row(shieldId = 8L)))
    }
}
