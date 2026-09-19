package com.jessemaddox.spoileralert.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.domain.ConversationIdentity
import com.jessemaddox.spoileralert.domain.EventIdentity
import com.jessemaddox.spoileralert.domain.EventMatchCandidate
import com.jessemaddox.spoileralert.ui.theme.Blue50
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.RadiusMd
import com.jessemaddox.spoileralert.ui.theme.TextAction
import java.text.DateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** Revealed notifications, grouped first by the protected event and local session date. */
@Composable
fun HistoryScreen(
    items: List<VaultEntity>,
    shields: List<ShieldEntity>,
    sportByShieldId: Map<Long, SportPresentation> = emptyMap(),
    releasedSinceMillis: Long? = null,
) {
    val groups = historyGroups(items, shields, releasedSinceMillis)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
    ) {
        if (releasedSinceMillis != null) {
            item(key = "catch-up-intro") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "What you missed",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Blueberry,
                    )
                    Text(
                        "These notifications arrived while spoilers were hidden. Open the source app to jump back into the conversation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JessColors.subtle,
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    "Nothing revealed yet. Items you reveal show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JessColors.subtle,
                )
            }
        }

        historyGroupItems(
            groups,
            initiallyExpanded = releasedSinceMillis != null,
            sportByShieldId = sportByShieldId,
        )

        item {
            Text(
                "Revealed items stay on this device for up to 1 year.",
                style = MaterialTheme.typography.labelSmall,
                color = JessColors.subtle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

internal fun historyGroups(
    items: List<VaultEntity>,
    shields: List<ShieldEntity>,
    releasedSinceMillis: Long? = null,
): List<HistoryEventGroup> {
    val candidates = shields.map { shield ->
        EventMatchCandidate(
            id = shield.id,
            name = shield.name,
            aliases = runCatching { ShieldCodec.decodeAliases(shield.aliasesJson).map { it.text } }
                .getOrDefault(emptyList()),
        )
    }
    val candidatesById = candidates.associateBy { it.id }
    val shieldsById = shields.associateBy { it.id }
    val revealedItems = items.filter { item ->
        val revealedAt = item.revealedAtMillis ?: return@filter false
        releasedSinceMillis == null || revealedAt >= releasedSinceMillis
    }
    return HistoryGrouping.group(
        revealedItems, ZoneId.systemDefault(),
        identity = { item ->
            if (shieldsById[item.shieldId]?.kind == "FANTASY") {
                return@group HistoryEventIdentity("fantasy", "Fantasy sports")
            }
            val text = "${item.title}\n${item.text}"
            val current = candidatesById[item.shieldId]
            val currentStillMatches = current != null &&
                EventIdentity.bestMatch(text, listOf(current)) != null
            val chosen = if (currentStillMatches) current
            else EventIdentity.bestMatch(text, candidates) ?: current
            val canonical = chosen?.let { EventIdentity.canonical(it.name) }
            HistoryEventIdentity(
                key = canonical?.key ?: "shield:${item.shieldId}",
                displayName = canonical?.displayName ?: "Past session",
                mergeAdjacentDates = canonical?.mergeAdjacentDates == true,
            )
        },
    )
}

internal fun androidx.compose.foundation.lazy.LazyListScope.historyGroupItems(
    groups: List<HistoryEventGroup>,
    initiallyExpanded: Boolean = false,
    sportByShieldId: Map<Long, SportPresentation> = emptyMap(),
) {
    groups.forEach { group ->
        item(key = "event-${group.eventKey}-${group.date}") {
            val sport = historySport(group, sportByShieldId)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Blue50),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        sport.glyph,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        group.eventName,
                        style = MaterialTheme.typography.titleLarge,
                        color = Blueberry,
                    )
                    Text(
                        "${sport.label.uppercase()} · ${DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(group.date).uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JessColors.accentInk,
                    )
                }
            }
        }
        val conversations = HiddenGrouping.groupByConversation(group.items)
        conversations.forEachIndexed { conversationIndex, conversation ->
            item(
                key = "conversation-${group.eventKey}-${group.date}-$conversationIndex",
            ) {
                HistoryConversationPanel(
                    conversation = conversation,
                    initiallyExpanded = initiallyExpanded,
                    modifier = Modifier.padding(start = 46.dp),
                )
            }
        }
    }
}

internal fun historySport(
    group: HistoryEventGroup,
    sportByShieldId: Map<Long, SportPresentation>,
): SportPresentation = EventPresentation.sportForEventKey(group.eventKey)
    ?: group.items.firstNotNullOfOrNull { sportByShieldId[it.shieldId] }
    ?: EventPresentation.sport(null)

@Composable
private fun HistoryConversationPanel(
    conversation: ConversationGroup,
    initiallyExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launch = remember(conversation.sourcePackage) {
        context.packageManager.getLaunchIntentForPackage(conversation.sourcePackage)
    }
    var expanded by rememberSaveable(
        conversation.sourcePackage,
        conversation.conversationLabel,
    ) { mutableStateOf(initiallyExpanded) }
    val title = conversation.conversationLabel ?: conversation.sourceAppLabel
    val source = conversation.sourceAppLabel.takeIf { conversation.conversationLabel != null }
    val count = conversation.messageCount
    val range = HiddenGrouping.timeRange(
        conversation.firstPostedAt,
        conversation.lastPostedAt,
        historyTimeFmt,
    )

    Column(
        modifier.fillMaxWidth()
            .border(1.dp, JessColors.hairline, androidx.compose.foundation.shape.RoundedCornerShape(RadiusMd))
            .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(RadiusMd)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusMd))
            .clickable { expanded = !expanded }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SourceAppIcon(conversation.sourcePackage, conversation.sourceAppLabel)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Blueberry,
                )
                Text(
                    listOfNotNull(
                        source,
                        "$count ${if (count == 1) "message" else "messages"}",
                        range,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = JessColors.subtle,
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = if (expanded) "Collapse conversation" else "Show conversation",
                tint = JessColors.subtle,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            HorizontalDivider(color = JessColors.hairline)
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                conversation.displayItems.forEach { item ->
                    RevealedMessage(
                        item,
                        conversationLabel = conversation.conversationLabel,
                        formatAsTranscript = conversation.isConversation,
                    )
                }
                if (launch != null) {
                    TextAction(
                        if (conversation.conversationLabel != null) {
                            "Open conversation in ${conversation.sourceAppLabel} for full history"
                        } else {
                            "Open ${conversation.sourceAppLabel}"
                        },
                        onClick = { context.startActivity(launch) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RevealedMessage(
    item: VaultEntity,
    conversationLabel: String?,
    formatAsTranscript: Boolean,
) {
    val canonicalTitle = ConversationIdentity.canonical(item.title)
    val transcript = if (formatAsTranscript) {
        parseConversationTranscript(item.text)
    } else {
        listOf(TranscriptLine(message = item.text))
    }
    val showTitle = item.title.isNotBlank() &&
        (conversationLabel == null || canonicalTitle != conversationLabel) &&
        transcript.none { it.speaker != null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showTitle) {
            Text(item.title, style = MaterialTheme.typography.labelLarge, color = Blueberry)
        }
        transcript.forEach { line ->
            if (line.isBlank) {
                Spacer(Modifier.size(4.dp))
            } else {
                Text(
                    buildTranscriptText(line),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = Blueberry,
                )
            }
        }
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.postedAtMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = JessColors.subtle,
        )
    }
}

internal data class TranscriptLine(
    val prefix: String = "",
    val speaker: String? = null,
    val separator: String = "",
    val message: String = "",
    val isBlank: Boolean = false,
)

private val speakerLine = Regex("^([^:]{1,50})(:\\s*)(.*)$")
private const val REPLY_PREFIX = "↪ You got a reply: "

/** Turns Android MessagingStyle text into a readable transcript without changing its content. */
internal fun parseConversationTranscript(text: String): List<TranscriptLine> =
    text.lines().map { raw ->
        if (raw.isBlank()) return@map TranscriptLine(isBlank = true)
        val prefix = if (raw.startsWith(REPLY_PREFIX)) REPLY_PREFIX else ""
        val candidate = raw.removePrefix(prefix)
        val match = speakerLine.matchEntire(candidate)
        if (match == null) {
            TranscriptLine(prefix = prefix, message = candidate)
        } else {
            TranscriptLine(
                prefix = prefix,
                speaker = match.groupValues[1],
                separator = match.groupValues[2],
                message = match.groupValues[3],
            )
        }
    }

private fun buildTranscriptText(line: TranscriptLine) =
    androidx.compose.ui.text.buildAnnotatedString {
        append(line.prefix)
        line.speaker?.let { speaker ->
            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                append(speaker)
                append(line.separator)
            }
        }
        append(line.message)
    }

private val historyTimeFmt: (Long) -> String = {
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
}

@Composable
internal fun SourceAppIcon(
    sourcePackage: String,
    sourceAppLabel: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appIcon = remember(sourcePackage) {
        runCatching {
            context.packageManager.getApplicationIcon(sourcePackage)
                .toBitmap(width = 64, height = 64)
                .asImageBitmap()
        }.getOrNull()
    }
    if (appIcon != null) {
        Image(
            bitmap = appIcon,
            contentDescription = "$sourceAppLabel icon",
            modifier = modifier.size(36.dp),
        )
    } else {
        androidx.compose.foundation.layout.Box(
            modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Blue50),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_shield),
                contentDescription = null,
                tint = JessColors.accentInk,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
