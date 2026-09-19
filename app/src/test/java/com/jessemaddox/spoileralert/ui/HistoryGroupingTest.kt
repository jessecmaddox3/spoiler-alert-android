package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryGroupingTest {
    private val zone = ZoneId.of("America/New_York")

    @Test fun `groups revealed notifications by event shield and local date`() {
        val friday = Instant.parse("2026-07-18T14:00:00Z").toEpochMilli()
        val saturday = Instant.parse("2026-07-19T14:00:00Z").toEpochMilli()
        val groups = HistoryGrouping.group(
            listOf(item(2, 7, friday + 1), item(1, 7, friday), item(3, 8, saturday)), zone,
            identity = {
                HistoryEventIdentity("shield:${it.shieldId}", "Shield ${it.shieldId}")
            },
        )

        assertEquals(listOf("shield:8", "shield:7"), groups.map { it.eventKey })
        assertEquals(listOf(1L, 2L), groups[1].items.map { it.id })
    }

    @Test fun `equivalent shield ids group under one canonical event identity`() {
        val friday = Instant.parse("2026-07-18T14:00:00Z").toEpochMilli()
        val groups = HistoryGrouping.group(
            listOf(item(1, 7, friday), item(2, 8, friday + 1)), zone,
            identity = { HistoryEventIdentity("golf:the-open", "The Open") },
        )

        assertEquals(1, groups.size)
        assertEquals("The Open", groups.single().eventName)
    }

    @Test fun `named event notifications on adjacent dates stay in one event group`() {
        val friday = Instant.parse("2026-07-18T14:00:00Z").toEpochMilli()
        val saturday = Instant.parse("2026-07-19T14:00:00Z").toEpochMilli()
        val groups = HistoryGrouping.group(
            listOf(item(1, 7, friday), item(2, 8, saturday)), zone,
            identity = {
                HistoryEventIdentity(
                    key = "golf:the-open",
                    displayName = "The Open",
                    mergeAdjacentDates = true,
                )
            },
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups.single().items.map { it.id })
        assertEquals(
            Instant.ofEpochMilli(friday).atZone(zone).toLocalDate(),
            groups.single().date,
        )
    }

    @Test fun `repeating generic protection stays separated by date`() {
        val friday = Instant.parse("2026-07-18T14:00:00Z").toEpochMilli()
        val saturday = Instant.parse("2026-07-19T14:00:00Z").toEpochMilli()
        val groups = HistoryGrouping.group(
            listOf(item(1, 7, friday), item(2, 7, saturday)), zone,
            identity = { HistoryEventIdentity("name:falcons", "Atlanta Falcons") },
        )

        assertEquals(2, groups.size)
    }

    private fun item(id: Long, shieldId: Long, posted: Long) = VaultEntity(
        id = id, shieldId = shieldId, sourcePackage = "com.test", sourceAppLabel = "Test",
        title = "title", text = "text", postedAtMillis = posted,
        notificationKey = "key-$id", revealedAtMillis = posted + 1,
    )
}
