package com.jessemaddox.spoileralert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventCompletionPolicyTest {
    @Test fun `final stops checks`() {
        assertNull(EventCompletionPolicy.nextDelayMillis(true, 0, Long.MAX_VALUE))
    }

    @Test fun `live or unavailable rechecks while protection has time left`() {
        val deadline = EventCompletionPolicy.RECHECK_MS * 2
        assertEquals(
            EventCompletionPolicy.RECHECK_MS,
            EventCompletionPolicy.nextDelayMillis(false, 0, deadline),
        )
        assertEquals(
            EventCompletionPolicy.RECHECK_MS,
            EventCompletionPolicy.nextDelayMillis(null, 0, deadline),
        )
    }

    @Test fun `does not fetch past protection deadline`() {
        assertNull(
            EventCompletionPolicy.nextDelayMillis(
                false,
                0,
                EventCompletionPolicy.RECHECK_MS,
            )
        )
    }
}
