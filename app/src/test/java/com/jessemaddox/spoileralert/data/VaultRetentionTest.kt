package com.jessemaddox.spoileralert.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultRetentionTest {
    @Test fun `retention cutoff follows the disclosed one year window`() {
        val now = 2_000_000_000_000L
        assertEquals(now - 365L * 24 * 60 * 60 * 1000, VaultRetention.cutoff(now))
    }
}
