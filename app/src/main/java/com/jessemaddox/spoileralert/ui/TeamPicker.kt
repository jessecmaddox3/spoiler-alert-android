package com.jessemaddox.spoileralert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.domain.League
import com.jessemaddox.spoileralert.domain.Team
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.ui.theme.Blue50
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.PillShape
import com.jessemaddox.spoileralert.ui.theme.PrimaryPill
import com.jessemaddox.spoileralert.ui.theme.RadiusLg
import com.jessemaddox.spoileralert.ui.theme.WoltBlue

/**
 * Rebuilt add flow (v3 amendment item 7): a full-height sheet with three entry tabs —
 * Teams (a multi-select builder with cross-league search, sheet persists across picks),
 * Games (the league-wide [GamesBrowser], protected one at a time as sessions), and
 * Something else (the custom keyword form). Selections in the Teams tab persist across
 * league navigation, search, and tab switches; only the sticky "Add N teams" tray commits
 * them as persistent DISARMED team shields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddShieldSheet(
    catalog: TeamCatalog,
    games: List<LeagueGameEntity>,
    leagueNames: Map<String, String>,
    onAddTeam: (Team) -> Unit,
    onProtectGame: (LeagueGameEntity) -> Unit,
    onProtectGameLater: (LeagueGameEntity) -> Unit,
    onOpenGame: (LeagueGameEntity) -> Unit,
    onRefresh: () -> Unit,
    scheduleStatus: String,
    onAddCustom: (name: String, keywords: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by rememberSaveable { mutableStateOf(0) }
    // Selected teams live at sheet scope so they survive league drill-down, search, and tab
    // switches — the whole point of the multi-select builder.
    val selected = remember { mutableStateListOf<Team>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        scrimColor = JessColors.scrim,
        shape = RoundedCornerShape(topStart = RadiusLg, topEnd = RadiusLg),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 14.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(JessColors.hairline, PillShape),
            )
        },
    ) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            SegmentedTabs(
                labels = listOf("Teams", "Events", "Custom"),
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
            when (tab) {
                0 -> TeamsTab(
                    catalog = catalog,
                    selected = selected,
                    onCommit = { picked -> picked.forEach(onAddTeam); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                1 -> Box(Modifier.weight(1f)) {
                    GamesBrowser(
                        games = games,
                        leagueNames = leagueNames,
                        catalog = catalog,
                        onProtect = onProtectGame,
                        onProtectLater = onProtectGameLater,
                        onOpenGame = onOpenGame,
                        onRefresh = onRefresh,
                        scheduleStatus = scheduleStatus,
                    )
                }
                else -> CustomShieldForm(
                    modifier = Modifier.weight(1f),
                    onAdd = { n, k -> onAddCustom(n, k); onDismiss() },
                )
            }
        }
    }
}

/** Blue-50 pill container, active segment = Wolt Blue fill with Blueberry ink. */
@Composable
private fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(Blue50, PillShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(PillShape)
                    .background(if (active) WoltBlue else Color.Transparent, PillShape)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) Blueberry else JessColors.subtle,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TeamsTab(
    catalog: TeamCatalog,
    selected: MutableList<Team>,
    onCommit: (List<Team>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var openLeagueId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMoreLeagues by rememberSaveable { mutableStateOf(false) }

    fun isSelected(team: Team) = selected.any { it.id == team.id }
    fun toggle(team: Team) {
        val existing = selected.firstOrNull { it.id == team.id }
        if (existing != null) selected.remove(existing) else selected.add(team)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WoltBlue,
        unfocusedBorderColor = JessColors.ghostStroke,
        focusedLabelColor = JessColors.accentInk,
        unfocusedLabelColor = JessColors.subtle,
        cursorColor = Blueberry,
    )

    Column(modifier.fillMaxWidth().imePadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search teams and events") },
            singleLine = true,
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(6.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                query.isNotBlank() -> {
                    val results = remember(query) { catalog.searchTeams(query) }
                    if (results.isEmpty()) {
                        EmptyHint("No teams match “${query.trim()}”.")
                    } else {
                        LazyColumn {
                            items(results, key = { it.second.id }) { (league, team) ->
                                TeamSelectRow(
                                    team = team,
                                    leagueId = league.id,
                                    qualifier = league.name,
                                    selected = isSelected(team),
                                    onToggle = { toggle(team) },
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
                openLeagueId == null -> {
                    val core = catalog.leagues.filter { it.id in TEAM_PICKER_CORE_LEAGUES }
                    val more = catalog.leagues.filterNot { it.id in TEAM_PICKER_CORE_LEAGUES }
                    LazyColumn {
                        items(core, key = { it.id }) { league ->
                            LeagueBrowseRow(
                                leagueId = league.id,
                                name = league.name,
                                onOpen = { openLeagueId = league.id },
                            )
                        }
                        if (more.isNotEmpty()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().clickable { showMoreLeagues = !showMoreLeagues }
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("➕", style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        if (showMoreLeagues) "Show fewer sports" else "More sports…",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Blueberry,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = null,
                                        tint = JessColors.subtle,
                                        modifier = Modifier.size(18.dp).graphicsLayer {
                                            rotationZ = if (showMoreLeagues) 90f else 0f
                                        },
                                    )
                                }
                            }
                        }
                        if (showMoreLeagues) {
                            items(more, key = { it.id }) { league ->
                                LeagueBrowseRow(
                                    leagueId = league.id,
                                    name = league.name,
                                    onOpen = { openLeagueId = league.id },
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
                else -> {
                    val league = catalog.leagues.first { it.id == openLeagueId }
                    LazyColumn {
                        item {
                            LeagueHeaderRow(league.name, onBack = { openLeagueId = null })
                        }
                        items(catalog.entriesForLeague(league.id), key = { it.id }) { team ->
                            TeamSelectRow(
                                team = team,
                                leagueId = league.id,
                                qualifier = league.name,
                                selected = isSelected(team),
                                onToggle = { toggle(team) },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }

        SelectionTray(
            selected = selected,
            onRemove = { team -> selected.firstOrNull { it.id == team.id }?.let(selected::remove) },
            onCommit = { onCommit(selected.toList()) },
        )
    }
}

/** Two-line identity + a circular select indicator; tapping toggles, never dismisses. */
@Composable
private fun TeamSelectRow(
    team: Team,
    leagueId: String,
    qualifier: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamArtwork(
            teamId = team.id,
            leagueId = leagueId,
            fallbackEmoji = EventPresentation.sport(leagueId).glyph,
            size = 42.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(team.name, style = MaterialTheme.typography.titleMedium, color = Blueberry)
            Text(qualifier, style = MaterialTheme.typography.labelSmall, color = JessColors.subtle)
        }
        SelectDot(selected)
    }
}

@Composable
private fun SelectDot(selected: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .clip(PillShape)
            .then(
                if (selected) Modifier.background(WoltBlue, PillShape)
                else Modifier.border(1.5.dp, JessColors.ghostStroke, PillShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Blueberry,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** League row in the browser: familiar sport emoji + league name. */
@Composable
private fun LeagueBrowseRow(leagueId: String, name: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(EventPresentation.sport(leagueId).glyph, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = Blueberry, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = JessColors.subtle,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Header inside an opened league: back affordance + league name eyebrow. */
@Composable
private fun LeagueHeaderRow(name: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = "Back to leagues",
            tint = JessColors.accentInk,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f },
        )
        Text(
            "All leagues  ·  $name",
            style = MaterialTheme.typography.labelMedium,
            color = JessColors.accentInk,
        )
    }
}

/** Sticky bottom tray: removable chips of picks + the Follow action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionTray(
    selected: List<Team>,
    onRemove: (Team) -> Unit,
    onCommit: () -> Unit,
) {
    val n = selected.size
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(JessColors.hairline))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (n > 0) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selected.forEach { team -> SelectedChip(team) { onRemove(team) } }
                }
                Spacer(Modifier.height(12.dp))
            }
            PrimaryPill(
                text = if (n == 0) "Choose teams to follow" else "Follow $n team${if (n == 1) "" else "s"}",
                enabled = n > 0,
                onClick = onCommit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val TEAM_PICKER_CORE_LEAGUES = setOf("nfl", "cfb", "nba", "mlb", "nhl", "epl", "mls")

/** Removable pick chip: team name + an x (ic_plus rotated 45°). */
@Composable
private fun SelectedChip(team: Team, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(PillShape)
            .background(Blue50, PillShape)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = "Remove ${team.name}" }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            team.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = JessColors.accentInk,
        )
        Icon(
            painterResource(R.drawable.ic_plus),
            contentDescription = null,
            tint = JessColors.accentInk,
            modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = 45f },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = JessColors.subtle,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.displaySmall,
        color = Blueberry,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun CustomShieldForm(onAdd: (String, List<String>) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    var keywords by rememberSaveable { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WoltBlue,
        unfocusedBorderColor = JessColors.ghostStroke,
        focusedLabelColor = JessColors.accentInk,
        unfocusedLabelColor = JessColors.subtle,
        cursorColor = Blueberry,
    )
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        SheetTitle("Something else")
        Text(
            "A golf major, an F1 race, a show finale — anything with a name and a few telltale words.",
            style = MaterialTheme.typography.bodyMedium,
            color = JessColors.subtle,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name (e.g. The Masters)") },
            singleLine = true,
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val parsed = keywords.split(Regex("[,;\n]")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        OutlinedTextField(
            value = keywords, onValueChange = { keywords = it },
            label = { Text("Keywords, comma-separated") },
            supportingText = {
                Text(
                    if (parsed.isEmpty()) "Separate keywords with commas. Plurals are matched automatically."
                    else "Will match: ${parsed.joinToString("  ·  ")}   (plus plurals)"
                )
            },
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryPill(
            text = "Follow this",
            enabled = name.isNotBlank() && parsed.isNotEmpty(),
            onClick = { onAdd(name.trim(), parsed) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}
