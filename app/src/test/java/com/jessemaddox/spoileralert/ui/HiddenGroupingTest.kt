package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenGroupingTest {

    private val base = 1_760_000_000_000L
    private val min = 60_000L

    private var seq = 0L
    private fun item(
        shieldId: Long,
        pkg: String,
        label: String,
        conversation: String?,
        postedAt: Long,
        title: String = "t",
        text: String = "body",
        messageCount: Int = 1,
        important: Boolean = false,
    ) = VaultEntity(
        id = ++seq,
        shieldId = shieldId,
        sourcePackage = pkg,
        sourceAppLabel = label,
        title = title,
        text = text,
        postedAtMillis = postedAt,
        notificationKey = "k$seq",
        conversation = conversation,
        messageCount = messageCount,
        isConversation = conversation != null,
        isImportantConversation = important,
    )

    // --- conversation grouping ---

    @Test
    fun `single-item conversation stays a group of one`() {
        val groups = HiddenGrouping.group(
            listOf(item(1, "com.wa", "WhatsApp", "Mom", base))
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].conversations.size)
        val c = groups[0].conversations[0]
        assertEquals(1, c.count)
        assertEquals("Mom", c.conversationLabel)
    }

    @Test
    fun `multi-item burst from same conversation collapses into one group sorted ascending`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.wa", "WhatsApp", "Family chat", base + 3 * min),
                item(1, "com.wa", "WhatsApp", "Family chat", base + 1 * min),
                item(1, "com.wa", "WhatsApp", "Family chat", base + 2 * min),
            )
        )
        assertEquals(1, groups[0].conversations.size)
        val c = groups[0].conversations[0]
        assertEquals(3, c.count)
        // sorted by postedAt ascending
        assertEquals(base + 1 * min, c.items.first().postedAtMillis)
        assertEquals(base + 3 * min, c.items.last().postedAtMillis)
    }

    @Test
    fun `distinct conversations in same app stay separate`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.wa", "WhatsApp", "Family chat", base),
                item(1, "com.wa", "WhatsApp", "Work", base + min),
            )
        )
        assertEquals(2, groups[0].conversations.size)
    }

    @Test
    fun `null-conversation items group by source app label`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.espn", "ESPN", null, base),
                item(1, "com.espn", "ESPN", null, base + min),
                item(1, "com.yahoo", "Yahoo", null, base + 2 * min),
            )
        )
        val labels = groups[0].conversations.map { it.sourceAppLabel to it.count }.toSet()
        assertTrue(labels.contains("ESPN" to 2))
        assertTrue(labels.contains("Yahoo" to 1))
        // null-conversation groups carry a null label (rendered as app-only)
        assertNull(groups[0].conversations.first { it.sourceAppLabel == "ESPN" }.conversationLabel)
    }

    @Test
    fun `blank conversation is treated as null`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.espn", "ESPN", "", base),
                item(1, "com.espn", "ESPN", null, base + min),
            )
        )
        assertEquals(1, groups[0].conversations.size)
        assertEquals(2, groups[0].conversations[0].count)
        assertNull(groups[0].conversations[0].conversationLabel)
    }

    @Test
    fun `conversations ordered by most-recent item first`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.wa", "WhatsApp", "Old", base + 1 * min),
                item(1, "com.wa", "WhatsApp", "New", base + 9 * min),
                item(1, "com.wa", "WhatsApp", "Mid", base + 5 * min),
            )
        )
        assertEquals(
            listOf("New", "Mid", "Old"),
            groups[0].conversations.map { it.conversationLabel },
        )
    }

    @Test
    fun `important conversations sort before newer ordinary conversations and alerts`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.espn", "ESPN", null, base + 10 * min),
                item(1, "com.wa", "WhatsApp", "New", base + 9 * min),
                item(1, "com.msg", "Messages", "Family", base, important = true),
            )
        )
        assertEquals(
            listOf("Family", "New", null),
            groups.single().conversations.map { it.conversationLabel },
        )
    }

    @Test
    fun `message count includes messages inside an updated notification`() {
        val convo = HiddenGrouping.group(
            listOf(item(1, "com.wa", "WhatsApp", "Family", base, messageCount = 4))
        ).single().conversations.single()
        assertEquals(1, convo.count)
        assertEquals(4, convo.messageCount)
    }

    @Test
    fun `rolling conversation titles group and cumulative counts are not added twice`() {
        val convo = HiddenGrouping.group(
            listOf(
                item(1, "com.wa", "WhatsApp", "Boys Club (2 messages)", base,
                    messageCount = 2),
                item(1, "com.wa", "WhatsApp", "Boys Club (3 messages): Dave", base + min,
                    messageCount = 3),
            )
        ).single().conversations.single()

        assertEquals("Boys Club", convo.conversationLabel)
        assertEquals(3, convo.messageCount)
        assertEquals(1, convo.displayItems.size)
        assertEquals(base + min, convo.displayItems.single().postedAtMillis)
    }

    @Test
    fun `rolling count in a legacy notification title repairs the displayed message count`() {
        val convo = HiddenGrouping.group(
            listOf(
                item(
                    1, "com.wa", "WhatsApp", "Boys Club", base,
                    title = "Boys Club (5 messages): David Lloyd", messageCount = 2,
                ),
            )
        ).single().conversations.single()

        assertEquals(5, convo.messageCount)
    }

    // --- session grouping ---

    @Test
    fun `items split into session groups by shield`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.wa", "WhatsApp", "A", base),
                item(2, "com.wa", "WhatsApp", "B", base + min),
            )
        )
        assertEquals(2, groups.size)
        assertEquals(setOf(1L, 2L), groups.map { it.shieldId }.toSet())
    }

    @Test
    fun `session groups ordered by most-recent item first`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.wa", "WhatsApp", "A", base + 1 * min),
                item(2, "com.wa", "WhatsApp", "B", base + 9 * min),
            )
        )
        assertEquals(listOf(2L, 1L), groups.map { it.shieldId })
    }

    @Test
    fun `session count sums its conversations`() {
        val groups = HiddenGrouping.group(
            listOf(
                item(1, "com.wa", "WhatsApp", "A", base),
                item(1, "com.wa", "WhatsApp", "A", base + min),
                item(1, "com.wa", "WhatsApp", "B", base + 2 * min),
            )
        )
        assertEquals(3, groups[0].count)
    }

    @Test
    fun `empty input yields no groups`() {
        assertTrue(HiddenGrouping.group(emptyList()).isEmpty())
    }

    // --- time-range formatting ---

    private val fmt: (Long) -> String = { millis ->
        // deterministic fake clock formatter: minutes since base -> "8:41 PM" style
        when (millis) {
            base + 41 * min -> "8:41 PM"
            base + 72 * min -> "9:12 PM"
            base + 500 * min -> "8:41 AM"
            else -> "x"
        }
    }

    @Test
    fun `time range shares meridiem across the pair`() {
        assertEquals(
            "8:41–9:12 PM",
            HiddenGrouping.timeRange(base + 41 * min, base + 72 * min, fmt),
        )
    }

    @Test
    fun `time range keeps both meridiems when they differ`() {
        assertEquals(
            "8:41 AM–9:12 PM",
            HiddenGrouping.timeRange(base + 500 * min, base + 72 * min, fmt),
        )
    }

    @Test
    fun `same-minute range collapses to a single time`() {
        assertEquals(
            "8:41 PM",
            HiddenGrouping.timeRange(base + 41 * min, base + 41 * min, fmt),
        )
    }

    @Test
    fun `spoken range reads between two times`() {
        assertEquals(
            "between 8:41 PM and 9:12 PM",
            HiddenGrouping.spokenTimeRange(base + 41 * min, base + 72 * min, fmt),
        )
    }

    @Test
    fun `spoken range reads at one time when same minute`() {
        assertEquals(
            "at 8:41 PM",
            HiddenGrouping.spokenTimeRange(base + 41 * min, base + 41 * min, fmt),
        )
    }
}
