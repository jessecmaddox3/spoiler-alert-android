package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchupNamesTest {

    private val now = 1_760_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun game(shortName: String, startMillis: Long, completed: Boolean = false) = GameEntity(
        id = "e-$shortName", shieldId = 7, name = shortName, shortName = shortName,
        startMillis = startMillis, completed = completed, fetchedAtMillis = 0L,
    )

    // --- sessionMatchup: armed session naming ("PROTECTED · BUF @ KC") ---

    @Test
    fun `game kicking off during the session names it`() {
        val name = MatchupNames.sessionMatchup(
            listOf(game("BUF @ KC", now + hour)), armedAtMillis = now, expiresAtMillis = now + 4 * hour,
        )
        assertEquals("BUF @ KC", name)
    }

    @Test
    fun `game that kicked off shortly before arming still names the session`() {
        val name = MatchupNames.sessionMatchup(
            listOf(game("BUF @ KC", now - 2 * hour)), armedAtMillis = now, expiresAtMillis = now + 4 * hour,
        )
        assertEquals("BUF @ KC", name)
    }

    @Test
    fun `game long before the session does not name it`() {
        assertNull(MatchupNames.sessionMatchup(
            listOf(game("BUF @ KC", now - 6 * hour)), armedAtMillis = now, expiresAtMillis = now + 4 * hour,
        ))
    }

    @Test
    fun `game after the session ends does not name it`() {
        assertNull(MatchupNames.sessionMatchup(
            listOf(game("BUF @ KC", now + 5 * hour)), armedAtMillis = now, expiresAtMillis = now + 4 * hour,
        ))
    }

    @Test
    fun `nearest kickoff to arm time wins when several games fall in the window`() {
        val name = MatchupNames.sessionMatchup(
            listOf(game("LATER", now + 3 * hour), game("SOON", now + hour)),
            armedAtMillis = now, expiresAtMillis = now + 4 * hour,
        )
        assertEquals("SOON", name)
    }

    @Test
    fun `completed games never name a session`() {
        assertNull(MatchupNames.sessionMatchup(
            listOf(game("BUF @ KC", now + hour, completed = true)),
            armedAtMillis = now, expiresAtMillis = now + 4 * hour,
        ))
    }

    @Test
    fun `no games - null falls back to shield name`() {
        assertNull(MatchupNames.sessionMatchup(emptyList(), now, now + 4 * hour))
    }

    // --- sheetMatchup: pre-arm session-sheet title ---

    @Test
    fun `imminent kickoff names the sheet`() {
        assertEquals(
            "BUF @ KC",
            MatchupNames.sheetMatchup(listOf(game("BUF @ KC", now + hour)), nowMillis = now),
        )
    }

    @Test
    fun `recently started game names the sheet`() {
        assertEquals(
            "BUF @ KC",
            MatchupNames.sheetMatchup(listOf(game("BUF @ KC", now - hour)), nowMillis = now),
        )
    }

    @Test
    fun `distant game does not name the sheet`() {
        assertNull(MatchupNames.sheetMatchup(listOf(game("BUF @ KC", now + 26 * hour)), nowMillis = now))
    }
}
