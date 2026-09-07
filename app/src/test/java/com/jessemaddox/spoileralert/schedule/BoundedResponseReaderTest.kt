package com.jessemaddox.spoileralert.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class BoundedResponseReaderTest {
    @Test fun `live golf leaderboard sized response fits the production bound`() {
        val response = "x".repeat(2_500_000)

        val read = BoundedResponseReader.read(StringReader(response))

        assertEquals(response.length, read?.length)
        assertTrue(MAX_SCHEDULE_RESPONSE_CHARS > response.length)
    }

    @Test fun `response beyond bound is rejected instead of returned as truncated json`() {
        assertNull(BoundedResponseReader.read(StringReader("12345"), maxChars = 4))
    }
}
