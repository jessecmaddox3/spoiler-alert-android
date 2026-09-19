package com.jessemaddox.spoileralert.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.RevealSelection
import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.Blue50
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.HairlineCard
import com.jessemaddox.spoileralert.ui.theme.PillShape
import com.jessemaddox.spoileralert.ui.theme.PromotedCard
import com.jessemaddox.spoileralert.ui.theme.StopAndRevealPill
import com.jessemaddox.spoileralert.ui.theme.TextAction
import com.jessemaddox.spoileralert.ui.theme.WoltBlue
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val timeFmt: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }

/**
 * Spoilers tab — currently-hidden items grouped by event and conversation.
 * Content never appears pre-reveal; a burst collapses to a metadata-only row. Revealed items live
 * in the History screen (reached from the quiet link at the bottom). No celebration on reveal.
 */
@Composable
fun VaultScreen(
    items: List<VaultEntity>,
    shields: List<ShieldEntity>,
    sportByShieldId: Map<Long, SportPresentation> = emptyMap(),
    onReveal: (Long) -> Unit,
    selections: Map<Long, RevealSelection>,
    onDisarmShield: (Long, RevealSelection) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val shieldById = shields.associateBy { it.id }
    val hidden = items.filter { it.revealedAtMillis == null }
    var historyRangeName by rememberSaveable { mutableStateOf(HistoryRange.WEEK.name) }
    val historyRange = runCatching { HistoryRange.valueOf(historyRangeName) }
        .getOrDefault(HistoryRange.WEEK)

    // Live countdown for the "Auto-stops in …" chip.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); now = System.currentTimeMillis() }
    }

    val sections = HiddenRecency.split(HiddenGrouping.group(hidden), shieldById, now)
    val sessions = sections.recent + sections.older
    val olderIds = sections.older.mapTo(mutableSetOf()) { it.shieldId }
    val olderCount = sections.older.sumOf { it.count }
    var showOlder by rememberSaveable { mutableStateOf(false) }

    // Calm, type-led reveal-all confirmation (NO takeover): "N revealed · protection stopped".
    var revealAllNotice by remember { mutableStateOf<String?>(null) }
    var revealAllCandidate by remember { mutableStateOf<Triple<Long, Int, RevealSelection>?>(null) }
    var revealedItem by remember { mutableStateOf<VaultEntity?>(null) }
    LaunchedEffect(revealAllNotice) {
        if (revealAllNotice != null) { delay(6_000); revealAllNotice = null }
    }

    // Which collapsed conversation rows are expanded (keyed per shield+conversation).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val recentHistory = items.filter { historyRange.includes(it, now) }
    val displayedHistoryGroups = historyGroups(recentHistory, shields)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
    ) {
        revealAllNotice?.let { notice ->
            item(key = "reveal-all-notice") {
                Text(
                    notice,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = JessColors.accentInk,
                )
            }
        }

        if (sessions.isEmpty()) {
            item(key = "waiting-empty") { WaitingEmptyState() }
        } else {
            item(key = "waiting-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (sections.recent.isEmpty()) "No recent hidden notifications" else "Hidden",
                        style = MaterialTheme.typography.titleLarge, color = Blueberry)
                    Text(
                        if (sections.recent.isEmpty()) "Older notifications are still hidden below."
                        else "Open one safely, or stop hiding to reveal everything from an event.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JessColors.subtle,
                    )
                }
            }
        }

        sessions.forEach { session ->
            if (session.shieldId == sections.older.firstOrNull()?.shieldId) {
                item(key = "older-hidden") {
                    HairlineCard {
                        Text("Older hidden", style = MaterialTheme.typography.titleMedium, color = Blueberry)
                        Text(
                            "${olderCount} notification${if (olderCount == 1) "" else "s"} from ${sections.older.size} past session${if (sections.older.size == 1) "" else "s"}. Still hidden.",
                            style = MaterialTheme.typography.bodySmall, color = JessColors.subtle,
                        )
                        TextAction(if (showOlder) "Collapse older hidden" else "Show older hidden", onClick = { showOlder = !showOlder })
                    }
                }
            }
            // No hidden contents or reveal accessibility actions are composed while collapsed.
            if (session.shieldId in olderIds && !showOlder) return@forEach
            val shield = shieldById[session.shieldId]
            val armed = HiddenRecency.isHiding(shield, now)
            val expiresAt = shield?.takeIf { armed }
                ?.let { ShieldCodec.expiresAtMillis(it) }

            item(key = "chips-${session.shieldId}") {
                PromotedCard {
                    EventIdentityHeader(
                        name = shield?.name ?: "Past session",
                        sport = sportByShieldId[session.shieldId],
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = JessColors.accentInk,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "${session.count} ${if (session.count == 1) "notification" else "notifications"} hidden",
                            style = MaterialTheme.typography.titleSmall,
                            color = JessColors.accentInk,
                        )
                    }
                    Text(
                        "Last hidden ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(session.lastPostedAt))}",
                        style = MaterialTheme.typography.bodySmall, color = JessColors.subtle,
                    )
                    val statusLine = when {
                        expiresAt != null -> "Hiding stops in ${SessionDurations.formatRemaining(expiresAt - now)}"
                        shield?.kind == "FANTASY" -> "Hidden while another event is active"
                        !armed -> "Hiding has ended. These notifications are still hidden."
                        else -> null
                    }
                    statusLine?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
                    }
                }
            }

            session.conversations.forEach { convo ->
                conversationItems(
                    shieldId = session.shieldId,
                    convo = convo,
                    expanded = expanded,
                    onReveal = { item ->
                        onReveal(item.id)
                        revealedItem = item
                    },
                )
            }

            item(key = "reveal-${session.shieldId}") {
                StopAndRevealPill(
                    text = if (armed) {
                        "Stop & reveal ${session.count} notification${if (session.count == 1) "" else "s"}"
                    } else {
                        "Reveal ${session.count} notification${if (session.count == 1) "" else "s"}"
                    },
                    onClick = { revealAllCandidate = selections[session.shieldId]?.let { Triple(session.shieldId, session.count, it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (sessions.isNotEmpty()) {
            item(key = "history-link") {
                TextAction("View revealed history  ›", onClick = onOpenHistory)
            }
        } else {
            item(key = "history-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recently revealed", style = MaterialTheme.typography.titleLarge, color = Blueberry)
                    Text(
                        "Notifications you've opened, grouped by event and conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JessColors.subtle,
                    )
                    HistoryRangeBar(historyRange) { historyRangeName = it.name }
                }
            }

            if (displayedHistoryGroups.isEmpty()) {
                item(key = "history-empty") {
                    Text(
                        historyRange.emptyCopy,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JessColors.subtle,
                    )
                }
            } else {
                historyGroupItems(displayedHistoryGroups, sportByShieldId = sportByShieldId)
            }

            item(key = "history-retention") {
                Text(
                    "Revealed history stays on this device for up to 1 year.",
                    style = MaterialTheme.typography.labelSmall,
                    color = JessColors.subtle,
                )
            }
        }
    }

    revealAllCandidate?.let { (shieldId, count, selection) ->
        AlertDialog(
            onDismissRequest = { revealAllCandidate = null },
            title = { Text("Stop & reveal $count notification${if (count == 1) "" else "s"}?") },
            text = { Text("They will move into revealed History so you can catch up. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDisarmShield(shieldId, selection)
                    revealAllCandidate = null
                }) { Text("Stop & reveal") }
            },
            dismissButton = {
                TextButton(onClick = { revealAllCandidate = null }) { Text("Keep hidden") }
            },
        )
    }

    revealedItem?.let { item ->
        AlertDialog(
            onDismissRequest = { revealedItem = null },
            title = { Text("Revealed") },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetaEyebrow(
                        sourceAppLabel = item.sourceAppLabel,
                        conversation = item.conversation,
                        timeText = timeFmt(item.postedAtMillis),
                    )
                    if (item.title.isNotBlank()) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(item.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "This message is now in History. Other messages remain hidden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JessColors.subtle,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { revealedItem = null }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun WaitingEmptyState() {
    PromotedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(WoltBlue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Blueberry,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Nothing waiting", style = MaterialTheme.typography.titleLarge, color = Blueberry)
                Text(
                    "Notifications we hide will wait here until you reveal them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JessColors.subtle,
                )
            }
        }
    }
}

@Composable
private fun EventIdentityHeader(
    name: String,
    sport: SportPresentation?,
) {
    val resolvedSport = sport ?: EventPresentation.sport(null)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(resolvedSport.glyph, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = Blueberry)
            Text(
                resolvedSport.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = JessColors.accentInk,
            )
        }
    }
}

@Composable
private fun HistoryRangeBar(
    selected: HistoryRange,
    onSelect: (HistoryRange) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(PillShape).background(Blue50).padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        HistoryRange.entries.forEach { range ->
            val active = range == selected
            Box(
                Modifier.weight(1f).sizeIn(minHeight = 40.dp).clip(PillShape)
                    .background(if (active) WoltBlue else Color.Transparent)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(range) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    range.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) Blueberry else JessColors.subtle,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/**
 * Emits a conversation into the Hidden list. A single-item conversation renders directly (no
 * collapse noise); a burst renders one collapsed metadata row that expands to per-item reveals.
 */
private fun LazyListScope.conversationItems(
    shieldId: Long,
    convo: ConversationGroup,
    expanded: MutableMap<String, Boolean>,
    onReveal: (VaultEntity) -> Unit,
) {
    if (convo.count == 1 && convo.messageCount == 1) {
        val single = convo.items.first()
        item(key = "item-${single.id}") {
            HiddenCard(
                single,
                onReveal = { onReveal(single) },
                modifier = Modifier.padding(start = 44.dp),
            )
        }
        return
    }

    val key = "$shieldId:${convo.sourcePackage}:${convo.conversationLabel}"
    val isOpen = expanded[key] == true
    item(key = "convo-$key") {
        ConversationCollapsedRow(
            convo = convo,
            expanded = isOpen,
            onToggle = { expanded[key] = !isOpen },
            modifier = Modifier.padding(start = 44.dp),
        )
    }
    if (isOpen) {
        convo.items.forEach { entry ->
            item(key = "item-${entry.id}") {
                HiddenCard(
                    entry,
                    onReveal = { onReveal(entry) },
                    modifier = Modifier.padding(start = 56.dp),
                    showSourceIcon = false,
                )
            }
        }
    }
}

/** Collapsed conversation: app + chat + count + time range only — never content. One TalkBack
 *  focus stop with a custom action to expand/collapse. */
@Composable
private fun ConversationCollapsedRow(
    convo: ConversationGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = HiddenGrouping.timeRange(convo.firstPostedAt, convo.lastPostedAt, timeFmt)
    val spoken = HiddenGrouping.spokenTimeRange(convo.firstPostedAt, convo.lastPostedAt, timeFmt)
    val from = listOfNotNull(convo.sourceAppLabel, convo.conversationLabel).joinToString(", ")
    val actionLabel = if (expanded) "Collapse this conversation" else "Expand to reveal individual notifications"
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription =
            "${convo.messageCount} hidden messages from $from, $spoken. Content remains hidden."
        customActions = listOf(CustomAccessibilityAction(actionLabel) { onToggle(); true })
    }
    HairlineCard(modifier = modifier.then(semantics).clickable(onClick = onToggle)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SourceAppIcon(convo.sourcePackage, convo.sourceAppLabel)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    convo.conversationLabel ?: convo.sourceAppLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = Blueberry,
                )
                Text(
                    listOfNotNull(
                        convo.sourceAppLabel.takeIf { convo.conversationLabel != null },
                        "${convo.messageCount} hidden",
                        range,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = JessColors.subtle,
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = JessColors.subtle,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
            )
        }
    }
}

/** Hidden card: metadata eyebrow, lock + "Content hidden" — content never appears pre-reveal.
 *  Per-item reveal is deliberately one tap: its label already states the exact, limited effect.
 *  TalkBack gets the same direct action from the card's single focus stop. */
@Composable
private fun HiddenCard(
    item: VaultEntity,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    showSourceIcon: Boolean = true,
) {
    val timeText = timeFmt(item.postedAtMillis)
    val meta = listOfNotNull(item.sourceAppLabel, item.conversation, timeText).joinToString(", ")
    val cardSemantics = modifier.clearAndSetSemantics {
        contentDescription = "Hidden notification. From $meta. Content remains hidden."
        customActions = listOf(
            CustomAccessibilityAction("Reveal and show this notification; keep hiding others") {
                onReveal(); true
            },
        )
    }
    HairlineCard(modifier = cardSemantics) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showSourceIcon) SourceAppIcon(item.sourcePackage, item.sourceAppLabel)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.conversation?.takeIf { it.isNotBlank() } ?: item.sourceAppLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = Blueberry,
                )
                Text(
                    listOfNotNull(
                        item.sourceAppLabel.takeIf { !item.conversation.isNullOrBlank() },
                        timeText,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = JessColors.subtle,
                )
            }
            TextAction("Reveal", onClick = onReveal)
        }
    }
}

/** "MESSAGES · FAMILY CHAT · 4:41 PM" metadata eyebrow. */
@Composable
private fun MetaEyebrow(
    sourceAppLabel: String,
    conversation: String?,
    timeText: String,
    color: androidx.compose.ui.graphics.Color = JessColors.accentInk,
) {
    val meta = listOfNotNull(sourceAppLabel, conversation, timeText).joinToString(" · ")
    Text(
        meta.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}
