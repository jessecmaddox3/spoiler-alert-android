package com.jessemaddox.spoileralert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.SessionExtension
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.VaultEntity
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.schedule.ScoreAnswer
import com.jessemaddox.spoileralert.schedule.ScoreCheckpoint
import com.jessemaddox.spoileralert.schedule.ScoreQuestion
import com.jessemaddox.spoileralert.schedule.ScoreQuestionCatalog
import com.jessemaddox.spoileralert.schedule.SkipAnswer
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.ActiveHidingBanner
import com.jessemaddox.spoileralert.ui.theme.GhostPill
import com.jessemaddox.spoileralert.ui.theme.JessCard
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.PrimaryPill
import com.jessemaddox.spoileralert.ui.theme.PromotedCard
import com.jessemaddox.spoileralert.ui.theme.SectionLabel
import com.jessemaddox.spoileralert.ui.theme.StatusChip
import com.jessemaddox.spoileralert.ui.theme.StopAndRevealPill
import com.jessemaddox.spoileralert.ui.theme.TextAction
import com.jessemaddox.spoileralert.ui.theme.WarningBanner
import com.jessemaddox.spoileralert.ui.theme.WoltBlue
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun NotificationItemScreen(
    item: VaultEntity?,
    onReveal: (Long) -> Unit,
    onOpenSource: (() -> Unit)? = null,
    opensExactConversation: Boolean = false,
) {
    var loading by remember(item?.id) { mutableStateOf(item == null) }
    LaunchedEffect(item?.id) {
        if (item == null) {
            delay(750)
            loading = false
        }
    }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (item == null) {
            Text(
                if (loading) "Loading notification..." else "This hidden notification is no longer available.",
                color = JessColors.subtle,
            )
            return@Column
        }
        Text(
            listOfNotNull(
                item.sourceAppLabel,
                item.conversation,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.postedAtMillis)),
            ).joinToString(" · ").uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = JessColors.accentInk,
        )
        if (item.revealedAtMillis == null) {
            JessCard {
                Text("Content hidden", style = MaterialTheme.typography.titleLarge)
                Text(
                    "This notification may contain a spoiler. Revealing only affects this one; the rest stay hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JessColors.subtle,
                )
                PrimaryPill("Reveal this", { onReveal(item.id) })
            }
        } else {
            JessCard {
                if (item.title.isNotBlank()) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                }
                Text(item.text, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "Revealed. Other hidden notifications stay in the Hidden tab.",
                style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle,
            )
            onOpenSource?.let {
                PrimaryPill(
                    if (opensExactConversation) "Open conversation for full history" else "Open ${item.sourceAppLabel}",
                    it,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtectionHomeScreen(
    shields: List<ShieldEntity>,
    status: ProtectionStatus,
    hiddenCounts: Map<Long, Int>,
    lastHiddenAtByShield: Map<Long, Long>,
    nextGames: Map<Long, GameEntity>,
    sessionGames: Map<Long, List<GameEntity>>,
    protectedGames: List<ProtectedLiveGame>,
    dailyFeed: DailyEventFeed,
    catalog: TeamCatalog,
    onFixListener: () -> Unit,
    onFixNotifications: () -> Unit,
    onQuickProtect: (ShieldEntity, GameEntity?) -> Unit,
    onEndProtection: (ShieldEntity) -> Unit,
    onExtend: (ShieldEntity) -> Unit,
    onOpenHidden: () -> Unit,
    onOpenGameStatus: (ProtectedLiveGame) -> Unit,
    onProtectAnotherGame: () -> Unit,
    onProtectFeatured: (com.jessemaddox.spoileralert.data.LeagueGameEntity) -> Unit,
) {
    var endCandidate by remember { mutableStateOf<ShieldEntity?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    val armed = shields.filter { HiddenRecency.isHiding(it, now) }
    val sealed = HiddenRecency.recentEndedShields(shields, lastHiddenAtByShield, now)
    val quietDay = armed.isEmpty() && sealed.isEmpty() &&
        dailyFeed.personal.isEmpty() && dailyFeed.interests.isEmpty() && dailyFeed.featured.isEmpty()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!status.listenerEnabled || !status.serviceConnected) {
            item {
                WarningBanner(
                    title = "Spoiler hiding is off",
                    body = "Android notification access is disconnected, so spoilers can get through.",
                    action = "Turn hiding back on",
                    onAction = onFixListener,
                )
            }
        }
        if (!status.notificationsEnabled) {
            item {
                WarningBanner(
                    title = "App updates are muted",
                    body = "Spoilers are still hidden, but session reminders cannot appear.",
                    action = "Turn on updates",
                    onAction = onFixNotifications,
                )
            }
        }

        if (quietDay) {
            item(key = "home-art-carousel") { HomeArtCarousel() }
        }

        if (armed.isNotEmpty()) {
            item { ActiveHidingBanner(Modifier.fillMaxWidth()) }
            armed.forEach { shield ->
                val liveGame = protectedGames.firstOrNull { game ->
                    shield.gameEventId == game.eventId ||
                        sessionGames[shield.id].orEmpty().any { it.id == game.eventId }
                }
                item(key = "armed-${shield.id}") {
                    JessCard {
                        EventTitle(
                            title = protectionName(shield, sessionGames),
                            leagueId = liveGame?.leagueId,
                            catalog = catalog,
                            homeEspnId = liveGame?.homeEspnId,
                            awayEspnId = liveGame?.awayEspnId,
                            savedTeamId = shield.catalogTeamId,
                        )
                        val intercepted = hiddenCounts[shield.id] ?: 0
                        val eventState = when {
                            liveGame == null -> null
                            liveGame.completed -> "Game is over"
                            liveGame.startMillis <= now -> "Game is live"
                            else -> "Starts ${shortTime(liveGame.startMillis)}"
                        }
                        eventState?.let { StatusChip(it) }
                        ShieldCodec.expiresAtMillis(shield)?.let { deadline ->
                            Text(
                                "Hiding notifications until ${shortTime(deadline)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Blueberry,
                            )
                            Text(
                                "${EventPresentation.friendlyDuration(deadline - now)} remaining",
                                style = MaterialTheme.typography.bodySmall,
                                color = JessColors.subtle,
                            )
                        }
                        if (intercepted > 0) {
                            Row(
                                Modifier.fillMaxWidth().clickable(onClick = onOpenHidden)
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "$intercepted notification${if (intercepted == 1) "" else "s"} hidden so far",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JessColors.accentInk,
                                )
                                Icon(
                                    painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = "Open Hidden",
                                    tint = JessColors.accentInk,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            Text(
                                "No notifications hidden yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = JessColors.subtle,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (liveGame != null) {
                                PrimaryPill(
                                    "Ask spoiler-free questions",
                                    { onOpenGameStatus(liveGame) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            ShieldCodec.sessionDeadlineMillis(shield)?.let { deadline ->
                                if (deadline - now <= THIRTY_MINUTES_MILLIS) {
                                    SessionExtension.plan(shield, now)?.let { extension ->
                                        GhostPill(
                                            "Extend hiding until ${shortTime(extension.sessionDeadlineMillis + ShieldCodec.GRACE_MS)}",
                                            { onExtend(shield) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            StopAndRevealPill(
                                text = if (intercepted > 0) {
                                    "Stop & reveal $intercepted notification${if (intercepted == 1) "" else "s"}"
                                } else {
                                    "Stop hiding"
                                },
                                onClick = { endCandidate = shield },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        if (dailyFeed.personal.isNotEmpty() || dailyFeed.interests.isNotEmpty()) {
            item { HomeSectionTitle("For you today") }
            dailyFeed.personal.forEach { suggestion ->
                item(key = "personal-${suggestion.shield.id}-${suggestion.game.id}") {
                    PromotedCard {
                        EventTitle(
                            suggestion.game.name,
                            suggestion.leagueId,
                            catalog = catalog,
                            savedTeamId = suggestion.shield.catalogTeamId,
                        )
                        EventTimingLines(
                            startMillis = suggestion.game.startMillis,
                            endMillis = null,
                            leagueId = suggestion.leagueId,
                            nowMillis = now,
                        )
                        PrimaryPill(
                            "Start hiding notifications",
                            { onQuickProtect(suggestion.shield, suggestion.game) },
                        )
                    }
                }
            }
            dailyFeed.interests.forEach { suggestion ->
                item(key = "interest-${suggestion.shield.id}-${suggestion.game.eventId}") {
                    PromotedCard {
                        EventTitle(
                            suggestion.game.shortName,
                            suggestion.game.leagueId,
                            catalog = catalog,
                            homeEspnId = suggestion.game.homeEspnId,
                            awayEspnId = suggestion.game.awayEspnId,
                        )
                        Text(
                            "Following",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JessColors.subtle,
                        )
                        EventTimingLines(
                            suggestion.game.startMillis, suggestion.game.endMillis,
                            suggestion.game.leagueId, now,
                        )
                        PrimaryPill(
                            "Start hiding notifications",
                            { onProtectFeatured(suggestion.game) },
                        )
                    }
                }
            }
        }

        if (dailyFeed.featured.isNotEmpty()) {
            item { HomeSectionTitle("Major events today") }
            dailyFeed.featured.forEach { suggestion ->
                item(key = "featured-${suggestion.game.eventId}") {
                    PromotedCard {
                        EventTitle(
                            suggestion.game.shortName,
                            suggestion.game.leagueId,
                            catalog = catalog,
                            homeEspnId = suggestion.game.homeEspnId,
                            awayEspnId = suggestion.game.awayEspnId,
                        )
                        Text(
                            suggestion.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = JessColors.subtle,
                        )
                        EventTimingLines(
                            suggestion.game.startMillis, suggestion.game.endMillis,
                            suggestion.game.leagueId, now,
                        )
                        PrimaryPill(
                            "Start hiding notifications",
                            { onProtectFeatured(suggestion.game) },
                        )
                    }
                }
            }
        }

        if (sealed.isNotEmpty()) {
            item { HomeSectionTitle("Recent hidden") }
            sealed.forEach { shield ->
                item(key = "sealed-${shield.id}") {
                    JessCard {
                        Text(shield.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${hiddenCounts[shield.id]} hidden notification${if (hiddenCounts[shield.id] == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Last hidden ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(lastHiddenAtByShield.getValue(shield.id)))}",
                            style = MaterialTheme.typography.bodySmall, color = JessColors.subtle,
                        )
                        TextAction("Open Hidden", onOpenHidden)
                    }
                }
            }
        }

        item {
            JessCard {
                val sports = dailyFeed.otherToday.leagueIds
                    .map { EventPresentation.sport(it) }
                    .distinct()
                Text(
                    if (dailyFeed.otherToday.count > 0) "More events today"
                    else "Looking for something else?",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (dailyFeed.otherToday.count > 0) {
                        val sportText = sports.take(3).joinToString(", ") { "${it.glyph} ${it.label}" }
                        "${dailyFeed.otherToday.count} more across $sportText${if (sports.size > 3) " and more" else ""}."
                    } else {
                        "Search upcoming games and long-tail events without adding clutter to Home."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = JessColors.subtle,
                )
                GhostPill(
                    if (dailyFeed.otherToday.count > 0) "Browse today's events" else "Find an event",
                    onProtectAnotherGame,
                )
            }
        }
    }

    endCandidate?.let { shield ->
        AlertDialog(
            onDismissRequest = { endCandidate = null },
            title = { Text("Stop hiding notifications for ${protectionName(shield, sessionGames)}?") },
            text = {
                Text(
                    "This stops hiding and reveals ${hiddenCounts[shield.id] ?: 0} hidden notification${if ((hiddenCounts[shield.id] ?: 0) == 1) "" else "s"}. Anything also covered by another active event stays hidden."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEndProtection(shield)
                    endCandidate = null
                }) {
                    Text(
                        if ((hiddenCounts[shield.id] ?: 0) > 0) {
                            val count = hiddenCounts[shield.id] ?: 0
                            "Stop & reveal $count notification${if (count == 1) "" else "s"}"
                        } else {
                            "Stop hiding"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { endCandidate = null }) { Text("Keep hiding") }
            },
        )
    }
}

@Composable
private fun HomeArtCarousel() {
    val artwork = HomeHeaderArtCatalog.featured
    val initialPage = remember { HomeHeaderArtCatalog.pageForDay(LocalDate.now().toEpochDay()) }
    val pagerState = rememberPagerState(initialPage = initialPage) { artwork.size }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("READY WHEN YOU ARE", style = MaterialTheme.typography.labelMedium, color = JessColors.accentInk)
        Text("Not hiding anything right now", style = MaterialTheme.typography.headlineSmall, color = Blueberry)
        Text(
            "Nothing you follow is happening today.",
            style = MaterialTheme.typography.bodyMedium,
            color = JessColors.subtle,
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = 8.dp,
            key = { artwork[it].assetName },
        ) { page ->
            HomeHeaderImage(artwork[page])
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "SPOILER ALERT",
                    style = MaterialTheme.typography.labelMedium,
                    color = JessColors.accentInk,
                )
                Text("WATCH ON YOUR TIME", style = MaterialTheme.typography.titleMedium, color = Blueberry)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                artwork.indices.forEach { index ->
                    Box(
                        Modifier
                            .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                            .background(
                                if (pagerState.currentPage == index) JessColors.accentInk
                                else JessColors.hairline,
                                CircleShape,
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeaderImage(artwork: HomeHeaderArtwork) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2.35f / 1f)
            .clip(RoundedCornerShape(24.dp))
            .background(WoltBlue),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_shield),
            contentDescription = null,
            tint = Blueberry,
            modifier = Modifier.size(44.dp),
        )
    }
}

@Composable
private fun HomeSectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.headlineMedium,
        color = JessColors.accentInk,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EventTitle(
    title: String,
    leagueId: String?,
    catalog: TeamCatalog,
    homeEspnId: String? = null,
    awayEspnId: String? = null,
    savedTeamId: String? = null,
) {
    val sport = EventPresentation.sport(leagueId)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leagueId != null && (!homeEspnId.isNullOrBlank() || !awayEspnId.isNullOrBlank())) {
            MatchupArtwork(
                leagueId = leagueId,
                homeTeamId = homeEspnId?.let { catalog.artworkId(leagueId, it) },
                awayTeamId = awayEspnId?.let { catalog.artworkId(leagueId, it) },
                fallbackEmoji = sport.glyph,
            )
        } else {
            TeamArtwork(savedTeamId, leagueId, sport.glyph)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Blueberry)
            Text(
                sport.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = JessColors.accentInk,
            )
        }
    }
}

@Composable
private fun EventTimingLines(
    startMillis: Long,
    endMillis: Long?,
    leagueId: String?,
    nowMillis: Long,
) {
    val timing = EventPresentation.timing(
        startMillis, endMillis, leagueId, nowMillis, ZoneId.systemDefault(),
    )
    Text(timing.schedule, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    Text(timing.relative, style = MaterialTheme.typography.bodyMedium, color = JessColors.subtle)
}

private fun shortTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private const val THIRTY_MINUTES_MILLIS = 30L * 60 * 1000

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtectionTeamsScreen(
    shields: List<ShieldEntity>,
    hiddenCounts: Map<Long, Int>,
    nextGames: Map<Long, GameEntity>,
    catalog: TeamCatalog,
    onProtectCustom: (ShieldEntity) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    val saved = shields.filter { it.kind == "TEAM" || it.kind == "CUSTOM" }
        .sortedBy { it.name.lowercase() }
    var deleteCandidate by remember { mutableStateOf<ShieldEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Follow teams and recurring events so their games appear on Home. Following does not hide notifications until you start it for a specific event.",
                style = MaterialTheme.typography.bodyMedium,
                color = JessColors.subtle,
                modifier = Modifier.weight(1f),
            )
            PrimaryPill("+ Follow more", onAdd, compact = true)
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            saved.forEach { shield ->
                item(key = shield.id) {
                    JessCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val leagueId = catalog.leagueIdForTeam(shield.catalogTeamId)
                            TeamArtwork(
                                teamId = shield.catalogTeamId,
                                leagueId = leagueId,
                                fallbackEmoji = EventPresentation.sport(leagueId).glyph,
                                size = 44.dp,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(shield.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        shield.armed -> "Following · Hiding notifications now"
                                        (hiddenCounts[shield.id] ?: 0) > 0 -> "Following · ${hiddenCounts[shield.id]} notification${if (hiddenCounts[shield.id] == 1) "" else "s"} waiting"
                                        else -> nextGames[shield.id]?.let { "Following · Next: ${it.shortName}" } ?: "Following"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JessColors.subtle,
                                )
                            }
                            if (shield.kind == "CUSTOM" && !shield.armed) {
                                GhostPill("Start hiding", { onProtectCustom(shield) })
                            }
                        }
                        TextAction("Remove", { deleteCandidate = shield })
                    }
                }
            }
        }
    }
    deleteCandidate?.let { shield ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Remove ${shield.name}?") },
            text = {
                Text(
                    if ((hiddenCounts[shield.id] ?: 0) > 0)
                        "This also reveals ${hiddenCounts[shield.id]} hidden item${if (hiddenCounts[shield.id] == 1) "" else "s"}."
                    else "You can add it again anytime."
                )
            },
            confirmButton = { TextButton(onClick = { onDelete(shield.id); deleteCandidate = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameStatusScreen(
    game: ProtectedLiveGame,
    scoreCheck: ScoreCheckState?,
    timelineState: GameTimelineState?,
    onAsk: (ScoreQuestion, forceRefresh: Boolean) -> Unit,
    onLoadTimeline: (forceRefresh: Boolean) -> Unit,
    onAskEarlier: (ScoreCheckpoint, ScoreQuestion) -> Unit,
    onCheckSkip: (ScoreCheckpoint, ScoreCheckpoint, forceRefresh: Boolean) -> Unit,
) {
    val answers = remember(game.eventId) { mutableStateMapOf<ScoreQuestion, ScoreCheckState>() }
    LaunchedEffect(scoreCheck) {
        if (scoreCheck?.loading == false && scoreCheck.answer != null) {
            answers[scoreCheck.question] = scoreCheck
        }
    }
    val questions = ScoreQuestionCatalog.forLeague(game.leagueId)
    val supportsTimeline = game.leagueId !in setOf("golf", "tgl", "f1")
    var delayTool by remember(game.eventId) { mutableStateOf(0) }
    LaunchedEffect(game.eventId, supportsTimeline) {
        if (supportsTimeline) onLoadTimeline(false)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(game.shortName, style = MaterialTheme.typography.headlineSmall, color = Blueberry)
            Text(
                "Choose any question below. Scores, leaders, and winners stay hidden unless the answer itself requires them.",
                style = MaterialTheme.typography.bodyMedium,
                color = JessColors.subtle,
            )
        }
        if (supportsTimeline) {
            item(key = "delay-heading") {
                Text("WATCHING ON DELAY", style = MaterialTheme.typography.labelMedium, color = JessColors.accentInk)
            }
            item(key = "delay-tools") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DelayToolCard(
                        title = "Can I skip ahead?",
                        description = "Choose the stretch you would skip. We only tell you whether a score happens.",
                        expanded = delayTool == 1,
                        onToggle = { delayTool = if (delayTool == 1) 0 else 1 },
                    ) {
                        SkipAheadTool(
                            state = timelineState,
                            onCheck = onCheckSkip,
                            onRefresh = { onLoadTimeline(true) },
                        )
                    }
                    DelayToolCard(
                        title = "Ask about an earlier point",
                        description = "Pick where you are in the game, then ask without seeing the score.",
                        expanded = delayTool == 2,
                        onToggle = { delayTool = if (delayTool == 2) 0 else 2 },
                    ) {
                        EarlierPointTool(
                            state = timelineState,
                            questions = questions,
                            onAsk = onAskEarlier,
                            onRefresh = { onLoadTimeline(true) },
                        )
                    }
                }
            }
        }
        questions.forEachIndexed { index, spec ->
            if (index == 0) {
                item(key = "quick-heading") {
                    Text("QUICK CHECKS", style = MaterialTheme.typography.labelMedium, color = JessColors.accentInk)
                }
            } else if (index == 3) {
                item(key = "more-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("MORE SPOILER-FREE QUESTIONS", style = MaterialTheme.typography.labelMedium, color = JessColors.accentInk)
                        Text(
                            "All available questions for this sport are shown below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JessColors.subtle,
                        )
                    }
                }
            }
            val question = spec.question
            item(key = question.name) {
                JessCard {
                    Text(spec.label, style = MaterialTheme.typography.titleMedium)
                    val loading = scoreCheck?.loading == true && scoreCheck.question == question
                    val result = answers[question]
                    if (result != null) {
                        Text(
                            result.detail ?: when {
                                result.sourceUnavailable -> "Couldn't get a fresh update"
                                else -> when (result.answer) {
                                ScoreAnswer.YES -> "Yes"
                                ScoreAnswer.NO -> "No"
                                ScoreAnswer.UNAVAILABLE -> "Not available yet"
                                null -> "Not available yet"
                                }
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Blueberry,
                        )
                        result.checkedAtMillis?.let { checkedAt ->
                            Text(
                                "Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(checkedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = JessColors.subtle,
                            )
                        }
                        if (loading) {
                            ScoreRefreshIndicator("Checking for an update…")
                        } else {
                            TextAction("Check again", { onAsk(question, true) })
                        }
                    } else {
                        if (loading) {
                            ScoreRefreshIndicator("Checking…")
                        } else {
                            GhostPill("Ask", { onAsk(question, false) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DelayToolCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    JessCard {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Blueberry)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = JessColors.subtle)
        if (expanded) {
            content()
            TextAction("Close", onToggle)
        } else {
            GhostPill("Open", onToggle)
        }
    }
}

@Composable
private fun SkipAheadTool(
    state: GameTimelineState?,
    onCheck: (ScoreCheckpoint, ScoreCheckpoint, forceRefresh: Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val checkpoints = state?.checkpoints.orEmpty()
    when {
        state == null || state.loading -> ScoreRefreshIndicator("Loading game timeline…")
        state.sourceUnavailable || checkpoints.size < 2 -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "A detailed play timeline is not available for this event yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = JessColors.subtle,
            )
            TextAction("Check again", onRefresh)
        }
        else -> {
            var selectedRange by remember(checkpoints) {
                mutableStateOf(0f..checkpoints.lastIndex.toFloat())
            }
            val startIndex = selectedRange.start.roundToInt().coerceIn(checkpoints.indices)
            val endIndex = selectedRange.endInclusive.roundToInt().coerceIn(checkpoints.indices)
            val start = checkpoints[startIndex]
            val end = checkpoints[endIndex]
            Text("I’m currently at", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            Text(start.label, style = MaterialTheme.typography.titleMedium, color = Blueberry)
            Text("I want to skip to", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            Text(end.label, style = MaterialTheme.typography.titleMedium, color = Blueberry)
            RangeSlider(
                value = selectedRange,
                onValueChange = { selectedRange = it },
                valueRange = 0f..checkpoints.lastIndex.toFloat(),
                steps = (checkpoints.size - 2).coerceAtLeast(0),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Don’t let me miss", style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
                StatusChip("Any score")
            }
            when {
                state.skipLoading -> ScoreRefreshIndicator("Checking this stretch…")
                state.skipAnswer != null && state.skipStartId == start.id && state.skipEndId == end.id -> {
                    val (headline, supporting, color) = when (state.skipAnswer) {
                        SkipAnswer.SAFE -> Triple(
                            "Safe to skip ahead",
                            "No scoring happens during this stretch.",
                            JessColors.revealGreen,
                        )
                        SkipAnswer.DO_NOT_SKIP -> Triple(
                            "Don’t skip this stretch",
                            "Something you asked not to miss happens here.",
                            JessColors.protectionRed,
                        )
                        SkipAnswer.UNAVAILABLE -> Triple(
                            "Couldn’t check this stretch",
                            "The play history is incomplete or still updating.",
                            JessColors.subtle,
                        )
                    }
                    Text(headline, style = MaterialTheme.typography.titleLarge, color = color)
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = JessColors.subtle)
                    state.skipCheckedAtMillis?.let { checkedAt ->
                        Text(
                            "Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(checkedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = JessColors.subtle,
                        )
                    }
                    TextAction("Check again", { onCheck(start, end, true) })
                }
                else -> PrimaryPill(
                    text = "Check this stretch",
                    onClick = { onCheck(start, end, false) },
                    enabled = endIndex > startIndex,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EarlierPointTool(
    state: GameTimelineState?,
    questions: List<com.jessemaddox.spoileralert.schedule.ScoreQuestionSpec>,
    onAsk: (ScoreCheckpoint, ScoreQuestion) -> Unit,
    onRefresh: () -> Unit,
) {
    val checkpoints = state?.checkpoints.orEmpty()
    when {
        state == null || state.loading -> ScoreRefreshIndicator("Loading game timeline…")
        state.sourceUnavailable || checkpoints.isEmpty() -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "An earlier game state is not available for this event yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = JessColors.subtle,
            )
            TextAction("Check again", onRefresh)
        }
        else -> {
            var selectedIndex by remember(checkpoints) { mutableStateOf(checkpoints.lastIndex.toFloat()) }
            val checkpoint = checkpoints[selectedIndex.roundToInt().coerceIn(checkpoints.indices)]
            Text("My viewing point", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            Text(checkpoint.label, style = MaterialTheme.typography.titleLarge, color = Blueberry)
            Slider(
                value = selectedIndex,
                onValueChange = { selectedIndex = it },
                valueRange = 0f..checkpoints.lastIndex.toFloat(),
                steps = (checkpoints.size - 2).coerceAtLeast(0),
            )
            val usefulQuestions = questions.filter {
                it.question in setOf(
                    ScoreQuestion.ANY_SCORE,
                    ScoreQuestion.TIED,
                    ScoreQuestion.CLOSE,
                    ScoreQuestion.BLOWOUT,
                    ScoreQuestion.OVER,
                )
            }
            Text("Ask as of this point", style = MaterialTheme.typography.labelMedium, color = JessColors.subtle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usefulQuestions.forEach { spec ->
                    GhostPill(
                        text = spec.label,
                        onClick = { onAsk(checkpoint, spec.question) },
                        enabled = state.historicalLoading.not(),
                    )
                }
            }
            if (state.historicalLoading) {
                ScoreRefreshIndicator("Checking that point…")
            } else if (state.historicalCheckpointId == checkpoint.id) {
                state.historicalAnswer?.let { result ->
                    Text(
                        when (result.answer) {
                            ScoreAnswer.YES -> "Yes"
                            ScoreAnswer.NO -> "No"
                            ScoreAnswer.UNAVAILABLE, null -> "Not available for that point"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Blueberry,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreRefreshIndicator(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = JessColors.accentInk,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
    }
}

private fun protectionName(shield: ShieldEntity, sessionGames: Map<Long, List<GameEntity>>): String {
    val armedAt = shield.armedAtMillis ?: return shield.name
    val expiresAt = ShieldCodec.expiresAtMillis(shield) ?: return shield.name
    return MatchupNames.sessionMatchup(sessionGames[shield.id].orEmpty(), armedAt, expiresAt) ?: shield.name
}
