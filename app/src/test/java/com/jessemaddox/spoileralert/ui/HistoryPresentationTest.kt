package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.data.ShieldEntity
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPresentationTest {
    @Test fun `canonical event sport wins when original shield sport is unknown`() {
        val group = HistoryEventGroup(
            eventKey = "golf:the-open",
            eventName = "The Open",
            date = LocalDate.of(2026, 7, 19),
            items = listOf(item(shieldId = 9)),
        )

        assertEquals(
            SportPresentation("Golf", "⛳"),
            historySport(group, mapOf(9L to SportPresentation("Sport", "★"))),
        )
    }

    @Test fun `unknown event keeps shield sport when available`() {
        val group = HistoryEventGroup(
            eventKey = "name:atlanta-falcons",
            eventName = "Atlanta Falcons",
            date = LocalDate.of(2026, 7, 19),
            items = listOf(item(shieldId = 4)),
        )

        assertEquals(
            SportPresentation("Football", "🏈"),
            historySport(group, mapOf(4L to SportPresentation("Football", "🏈"))),
        )
    }

    @Test fun `existing Open rows from adjacent days and shield ids regroup together`() {
        val july18 = Instant.parse("2026-07-18T16:00:00Z").toEpochMilli()
        val july19 = Instant.parse("2026-07-19T16:00:00Z").toEpochMilli()
        val shields = listOf(
            shield(4, "The Open Championship"),
            shield(9, "The Open"),
        )
        val groups = historyGroups(
            items = listOf(
                item(4).copy(
                    id = 1,
                    title = "The Open leaderboard",
                    postedAtMillis = july18,
                    revealedAtMillis = july18 + 1,
                ),
                item(9).copy(
                    id = 2,
                    title = "The Open final round",
                    postedAtMillis = july19,
                    revealedAtMillis = july19 + 1,
                ),
            ),
            shields = shields,
        )

        assertEquals(1, groups.size)
        assertEquals("golf:the-open", groups.single().eventKey)
        assertEquals(2, groups.single().items.size)
    }

    private fun shield(id: Long, name: String) = ShieldEntity(
        id = id,
        name = name,
        aliasesJson = """[{"text":"$name","short":false}]""",
        kind = "GAME",
    )

    private fun item(shieldId: Long) = VaultEntity(
        id = 1,
        shieldId = shieldId,
        sourcePackage = "com.test",
        sourceAppLabel = "Test",
        title = "Title",
        text = "Text",
        postedAtMillis = 1,
        notificationKey = "key",
        revealedAtMillis = 2,
    )
}
