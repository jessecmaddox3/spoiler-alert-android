package com.jessemaddox.spoileralert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskedNotifierTest {

    @Test fun `ids stay in the reserved 20k-40k range`() {
        for (rowId in listOf(0L, 1L, 7L, 19_999L, 20_000L, 123_456_789L, Long.MAX_VALUE / 2)) {
            val id = MaskedNotifier.idFor(rowId)
            assertTrue("rowId=$rowId -> $id", id in 20_000..39_999)
        }
    }

    @Test fun `concurrently plausible row ids never collide`() {
        // Row ids of items hidden at the same time are near each other; collisions need a 20k gap.
        val ids = (1_000L..2_000L).map { MaskedNotifier.idFor(it) }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun `id is stable for the same row`() {
        assertEquals(MaskedNotifier.idFor(42L), MaskedNotifier.idFor(42L))
    }
}
