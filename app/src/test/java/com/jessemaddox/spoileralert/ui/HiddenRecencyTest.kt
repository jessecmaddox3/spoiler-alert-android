package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.VaultEntity
import org.junit.Assert.*
import org.junit.Test

class HiddenRecencyTest {
    private val now = 1_789_000_000_000L
    private val day = 86_400_000L
    private val hour = 3_600_000L

    @Test fun `three week old Tour Championship does not earn a Home catch-up card`() {
        val tour = shield(1, "Tour Championship")
        val recent = shield(2, "Atlanta United")
        val result = HiddenRecency.recentEndedShields(
            listOf(tour, recent), mapOf(1L to now - 21 * day, 2L to now - hour), now,
        )
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test fun `old priority conversation stays available without outranking recent hidden items`() {
        val old = item(1, now - 21 * day).copy(isImportantConversation = true, messageCount = 80)
        val recent = item(2, now - hour)
        val split = HiddenRecency.split(HiddenGrouping.group(listOf(old, recent)), emptyMap(), now)
        assertEquals(listOf(2L), split.recent.map { it.shieldId })
        assertEquals(listOf(1L), split.older.map { it.shieldId })
        assertEquals(old, split.older.single().conversations.single().items.single())
        assertNull(old.revealedAtMillis)
    }

    @Test fun `expired armed flag cannot keep an old event prominent`() {
        val stale = shield(1, "Tour Championship").copy(armed = true, armedAtMillis = now - 21 * day)
        assertFalse(HiddenRecency.isHiding(stale, now))
        val split = HiddenRecency.split(HiddenGrouping.group(listOf(item(1, now - 21 * day))), mapOf(1L to stale), now)
        assertTrue(split.recent.isEmpty())
        assertEquals(1L, split.older.single().shieldId)
        assertTrue(HiddenRecency.recentEndedShields(listOf(stale), mapOf(1L to now - 21 * day), now).isEmpty())
    }

    @Test fun `valid hiding for an older recording remains visible`() {
        val active = shield(1, "Tour Championship").copy(armed = true, armedAtMillis = now - hour)
        val split = HiddenRecency.split(HiddenGrouping.group(listOf(item(1, now - 21 * day))), mapOf(1L to active), now)
        assertTrue(HiddenRecency.isHiding(active, now))
        assertEquals(1L, split.recent.single().shieldId)
        assertTrue(split.older.isEmpty())
        assertTrue(HiddenRecency.recentEndedShields(listOf(active), mapOf(1L to now - hour), now).isEmpty())
    }

    @Test fun `protection remains active during grace and ends at its absolute deadline`() {
        val active = shield(1, "Recording").copy(armed = true, armedAtMillis = now - 4 * hour)
        assertTrue(HiddenRecency.isHiding(active, now + hour - 1))
        assertFalse(HiddenRecency.isHiding(active, now + hour))
    }

    @Test fun `missing arm time does not imply active protection`() {
        assertFalse(HiddenRecency.isHiding(shield(1, "Missing time").copy(armed = true), now))
    }

    @Test fun `recent boundary ages out as time advances without any new notification`() {
        val s = shield(1, "Recording")
        val dates = mapOf(1L to now - 7 * day)
        assertEquals(listOf(1L), HiddenRecency.recentEndedShields(listOf(s), dates, now).map { it.id })
        assertTrue(HiddenRecency.recentEndedShields(listOf(s), dates, now + 1).isEmpty())
    }

    @Test fun `an expired session with new hidden items still offers recent catch-up`() {
        val s = shield(1, "Recording").copy(armed = true, armedAtMillis = now - day)
        assertEquals(listOf(1L), HiddenRecency.recentEndedShields(listOf(s), mapOf(1L to now - hour), now).map { it.id })
    }

    @Test fun `recent Home cards are newest first and omit empty sessions`() {
        val shields = listOf(shield(1, "Yesterday"), shield(2, "Today"), shield(3, "No hidden items"))
        assertEquals(listOf(2L, 1L), HiddenRecency.recentEndedShields(shields,
            mapOf(1L to now - day, 2L to now - hour), now).map { it.id })
    }

    @Test fun `a reused shield keeps old and recent hidden items in one reveal scope`() {
        val old = item(1, now - 21 * day)
        val recent = item(1, now - hour).copy(id = 2, notificationKey = "recent")
        val split = HiddenRecency.split(HiddenGrouping.group(listOf(old, recent)), emptyMap(), now)
        assertTrue(split.older.isEmpty())
        assertEquals(listOf(1L, 2L), split.recent.single().conversations.single().items.map { it.id })
        assertTrue(split.recent.single().conversations.single().items.all { it.revealedAtMillis == null })
    }

    private fun shield(id: Long, name: String) = ShieldEntity(id = id, name = name, aliasesJson = "[]", kind = "GAME")
    private fun item(shieldId: Long, posted: Long) = VaultEntity(
        id = shieldId, shieldId = shieldId, sourcePackage = "example.sports", sourceAppLabel = "Sports",
        title = "Unrevealed title", text = "Unrevealed body", postedAtMillis = posted, notificationKey = "k$shieldId",
    )
}
