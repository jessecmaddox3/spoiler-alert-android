package com.jessemaddox.spoileralert.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.data.GameWindows
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.HairlineCard
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.PillShape
import com.jessemaddox.spoileralert.ui.theme.PrimaryPill
import com.jessemaddox.spoileralert.ui.theme.ReminderPill
import com.jessemaddox.spoileralert.ui.theme.TextAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Full public event browser, independent of following, reminders, or active hiding. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GamesBrowser(
    games: List<LeagueGameEntity>,
    leagueNames: Map<String, String>,
    catalog: TeamCatalog,
    onProtect: (LeagueGameEntity) -> Unit,
    onProtectLater: (LeagueGameEntity) -> Unit,
    initialTodayOnly: Boolean = false,
    onOpenGame: (LeagueGameEntity) -> Unit,
    onRefresh: () -> Unit,
    scheduleStatus: String,
) {
    var leagueFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showMoreSports by rememberSaveable { mutableStateOf(false) }
    var window by rememberSaveable { mutableStateOf(if (initialTodayOnly) "Today" else "Upcoming") }
    val todayOnly = window == "Today"
    val listState = rememberLazyListState()
    val leagues = games.map { it.leagueId }.distinct()
    val normalizedQuery = query.trim().lowercase()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val filtered = GamesBrowsing.browse(games, query, leagueNames).filter { game ->
        (leagueFilter == null || game.leagueId == leagueFilter) &&
            when (window) {
                "Today" -> GamesBrowsing.happensOn(game, today, ZoneId.systemDefault())
                "Recent" -> game.startMillis <= now
                else -> game.startMillis > now
            }
    }
    val sections = if (todayOnly && filtered.isNotEmpty()) listOf(today to filtered)
        else GamesBrowsing.groupByDay(filtered, ZoneId.systemDefault()).let {
            if (window == "Recent") it.asReversed() else it
        }
    val coreLeagues = leagues.filter { it in CORE_LEAGUES }
    val moreLeagues = leagues.filterNot { it in CORE_LEAGUES }

    // Live "Started" tag: recompute every 60s so a row that just kicked off picks it up
    // without the whole browser needing a data refresh.
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); now = System.currentTimeMillis() }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
    ) {
        item {
            FlowRow(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Today", "Recent", "Upcoming").forEach { label ->
                    LeagueChip(label, selected = window == label, onClick = { window = label })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(scheduleStatus, modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
                TextAction("Refresh", onRefresh)
            }
            games.maxOfOrNull { it.fetchedAtMillis }?.takeIf { it > 0 }?.let { updated ->
                Text("Latest saved update: ${browserSavedFormat.format(Instant.ofEpochMilli(updated).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall, color = JessColors.subtle)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search teams, events, or sports") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Blueberry,
                    unfocusedBorderColor = JessColors.ghostStroke,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (leagues.size > 1) {
            item {
                FlowRow(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LeagueChip("All sports", selected = leagueFilter == null, onClick = { leagueFilter = null })
                    coreLeagues.forEach { id ->
                        LeagueChip(
                            "${EventPresentation.sport(id).glyph} ${leagueNames[id] ?: id.uppercase()}",
                            selected = leagueFilter == id,
                            onClick = { leagueFilter = id },
                        )
                    }
                }
            }
            if (moreLeagues.isNotEmpty()) {
                item {
                    TextAction(
                        if (showMoreSports) "Show fewer sports" else "More sports…",
                        onClick = { showMoreSports = !showMoreSports },
                    )
                }
                if (showMoreSports || leagueFilter in moreLeagues) {
                    item {
                        FlowRow(
                            Modifier.selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            moreLeagues.forEach { id ->
                                LeagueChip(
                                    "${EventPresentation.sport(id).glyph} ${leagueNames[id] ?: id.uppercase()}",
                                    selected = leagueFilter == id,
                                    onClick = { leagueFilter = id },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    GamesBrowsing.emptyStateMessage(
                        hasScheduleData = games.isNotEmpty(),
                        hasSearchOrSportFilter = normalizedQuery.isNotEmpty() || leagueFilter != null,
                        todayOnly = todayOnly,
                        recent = window == "Recent",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = JessColors.subtle,
                )
            }
        }
        sections.forEach { (day, dayGames) ->
            item(key = "day-$day") {
                Text(
                    dayLabel(day),
                    style = MaterialTheme.typography.titleMedium,
                    color = Blueberry,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(dayGames, key = { it.eventId }) { game ->
                GameRow(
                    game = game,
                    leagueName = leagueNames[game.leagueId] ?: game.leagueId.uppercase(),
                    catalog = catalog,
                    nowMillis = now,
                    onProtect = { onProtect(game) },
                    onProtectLater = { onProtectLater(game) },
                    onOpenGame = { onOpenGame(game) },
                )
            }
        }
    }
}

/** Neutral event metadata with separate Open game, Hide notifications, and reminder actions. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GameRow(
    game: LeagueGameEntity,
    leagueName: String,
    catalog: TeamCatalog,
    nowMillis: Long = System.currentTimeMillis(),
    onProtect: () -> Unit,
    onProtectLater: (() -> Unit)? = null,
    protectEnabled: Boolean = true,
    showProtect: Boolean = true,
    onOpenGame: (() -> Unit)? = null,
) {
    val started = GameWindows.hasStarted(game.startMillis, nowMillis)
    val sport = EventPresentation.sport(game.leagueId)
    val today = GamesBrowsing.happensOn(game, LocalDate.now(), ZoneId.systemDefault())
    HairlineCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MatchupArtwork(
                leagueId = game.leagueId,
                homeTeamId = catalog.artworkId(game.leagueId, game.homeEspnId),
                awayTeamId = catalog.artworkId(game.leagueId, game.awayEspnId),
                fallbackEmoji = sport.glyph,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    leagueName.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = JessColors.accentInk,
                )
                Text(game.shortName, style = MaterialTheme.typography.titleLarge, color = Blueberry)
                Text(
                    listOfNotNull(gameTime(game.startMillis), "Scheduled start has passed".takeIf { started }, game.label)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = JessColors.subtle,
                )
            }
        }
        if (showProtect) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                onOpenGame?.let { open -> PrimaryPill("Open game", open, compact = true) }
                if (today || started) {
                    com.jessemaddox.spoileralert.ui.theme.GhostPill(
                        "Hide notifications",
                        onClick = onProtect,
                        enabled = protectEnabled,
                    )
                }
                if (onProtectLater != null && !started) {
                    ReminderPill(
                        detail = reminderDetail(game.startMillis, nowMillis),
                        onClick = onProtectLater,
                    )
                }
            }
        }
    }
}

/** Selected = Blueberry pill, unselected = hairline stroke; 48dp targets, radio semantics. */
@Composable
private fun LeagueChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .sizeIn(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            if (selected) Modifier.clip(PillShape).background(Blueberry, PillShape)
            else Modifier
                .clip(PillShape)
                .border(1.5.dp, JessColors.hairline, PillShape)
                .background(Color.White, PillShape),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else Blueberry,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

private val browserSavedFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val browserTimeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val browserDayFormat = DateTimeFormatter.ofPattern("EEE · MMM d")
private val CORE_LEAGUES = setOf("nfl", "cfb", "nba", "mlb", "nhl", "epl", "mls", "soccer", "golf")

private fun gameTime(startMillis: Long): String =
    browserTimeFormat.format(Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()))

private fun reminderDetail(startMillis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val startDay = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, startDay)
    return when {
        days <= 0 -> "at ${gameTime(startMillis)}"
        days == 1L -> "tomorrow"
        else -> "in $days days"
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> browserDayFormat.format(day)
    }
}
