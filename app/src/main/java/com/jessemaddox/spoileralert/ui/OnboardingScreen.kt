package com.jessemaddox.spoileralert.ui

import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.domain.Team
import com.jessemaddox.spoileralert.domain.TeamCatalog
import com.jessemaddox.spoileralert.ui.theme.Blue50
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.Eyebrow
import com.jessemaddox.spoileralert.ui.theme.GhostPill
import com.jessemaddox.spoileralert.ui.theme.JessCard
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.PillShape
import com.jessemaddox.spoileralert.ui.theme.PrimaryPill
import com.jessemaddox.spoileralert.ui.theme.RadiusMd
import com.jessemaddox.spoileralert.ui.theme.TextAction
import com.jessemaddox.spoileralert.ui.theme.WoltBlue

private enum class OnbStep { Welcome, Disclosure, Battery, Demo }

/**
 * Team-first onboarding (v3 amendment item 4): pick team(s) → disclosure copy that NAMES the
 * pick → notification access → battery step ONLY when the OS restricts us → an optional live
 * protection demo, then a populated Home. The Play-required privacy facts stay verbatim in
 * meaning (on-device matching, public-sports-only network) — this is a policy surface.
 */
@Composable
fun OnboardingScreen(
    catalog: TeamCatalog,
    listenerEnabled: Boolean,
    demoPhase: DemoPhase,
    onAddTeams: (List<Team>) -> Unit,
    onGrantAccess: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRunDemo: (teamName: String) -> Unit,
    onRevealDemo: () -> Unit,
    onDemoTeardown: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }

    // Re-read OS state whenever we resume (returning from system settings) so the disclosure /
    // battery steps reflect the freshly granted state without needing a status change.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun ignoringBattery(): Boolean =
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    // The battery step exists only if the OS is restricting us at first run; decided once so it
    // never vanishes mid-flow once the user has granted it.
    val includeBattery = remember { !ignoringBattery() }
    val steps = remember(includeBattery) {
        buildList {
            add(OnbStep.Welcome)
            add(OnbStep.Disclosure)
            if (includeBattery) add(OnbStep.Battery)
            add(OnbStep.Demo)
        }
    }

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]

    // Belt-and-suspenders for the live abandonment case (the startup reconciliation in
    // SpoilerAlertApp covers process death / swipe): system Back must never drop out of a later
    // onboarding step — especially the demo, which may have ARMED a temporary session on the
    // user's real team shield — without tearing that demo down first. Stepping back through
    // onboarding both undoes any live demo (teardown is idempotent) and keeps the user in the
    // flow. At step 0 nothing has been armed, so Back falls through to the system default (exit).
    BackHandler(enabled = stepIndex > 0) {
        onDemoTeardown()
        stepIndex--
    }
    val selected = rememberSaveable(saver = listSaver<SnapshotStateList<Team>, String>(
        save = { teams -> teams.map { it.id } },
        restore = { ids ->
            val teams = catalog.leagues.flatMap { it.teams }.associateBy { it.id }
            ids.mapNotNull(teams::get).toMutableStateList()
        },
    )) { mutableStateListOf<Team>() }
    val primaryTeamName = selected.firstOrNull()?.name

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 24.dp),
    ) {
        StepDots(count = steps.size, current = stepIndex)
        Spacer(Modifier.height(16.dp))

        when (step) {
            OnbStep.Welcome -> WelcomeStep(
                catalog = catalog,
                selected = selected,
                onContinue = {
                    onAddTeams(selected.toList())
                    stepIndex++
                },
                modifier = Modifier.weight(1f),
            )
            OnbStep.Disclosure -> DisclosureStep(
                teamName = primaryTeamName,
                extraTeams = (selected.size - 1).coerceAtLeast(0),
                listenerEnabled = remember(resumeTick) { listenerEnabled },
                onGrantAccess = onGrantAccess,
                onContinue = {
                    onRequestNotifications() // request POST_NOTIFICATIONS after the disclosure
                    stepIndex++
                },
                modifier = Modifier.weight(1f),
            )
            OnbStep.Battery -> BatteryStep(
                ready = remember(resumeTick) { ignoringBattery() },
                onOpenSettings = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        )
                    )
                },
                onContinue = { stepIndex++ },
                modifier = Modifier.weight(1f),
            )
            OnbStep.Demo -> DemoStep(
                teamName = primaryTeamName,
                phase = demoPhase,
                onRun = { primaryTeamName?.let(onRunDemo) },
                onReveal = onRevealDemo,
                onFinish = onFinish,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Progress dots: current is a Wolt Blue pill, the rest hairline dots. */
@Composable
private fun StepDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (i == current) 22.dp else 6.dp)
                    .background(if (i <= current) WoltBlue else JessColors.hairline, PillShape)
            )
        }
    }
}

// --- Step 1: Welcome + team pick ---

@Composable
private fun WelcomeStep(
    catalog: TeamCatalog,
    selected: MutableList<Team>,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Eyebrow("Spoiler Alert")
        Spacer(Modifier.height(8.dp))
        Text(
            buildAnnotatedString {
                append("WATCH FIRST.\nREAD ")
                withStyle(SpanStyle(color = WoltBlue)) { append("AFTER") }
                append(".")
            },
            style = MaterialTheme.typography.displayLarge,
            color = Blueberry,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick the teams and events you follow. On game day, you decide when Spoiler Alert should start hiding notifications.",
            style = MaterialTheme.typography.bodyLarge,
            color = JessColors.subtle,
        )
        Spacer(Modifier.height(16.dp))
        OnboardingTeamPicker(
            catalog = catalog,
            selected = selected,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryPill(
            text = if (selected.isEmpty()) "Choose a team to continue"
            else "Follow ${selected.size} team${if (selected.size == 1) "" else "s"} and continue",
            enabled = selected.isNotEmpty(),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Compact search + league-browse multi-select, reusing [TeamCatalog.searchTeams]. */
@Composable
private fun OnboardingTeamPicker(
    catalog: TeamCatalog,
    selected: MutableList<Team>,
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

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search teams and events") },
            singleLine = true,
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SelectedRow(selected = selected, onRemove = { t -> selected.firstOrNull { it.id == t.id }?.let(selected::remove) })
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                query.isNotBlank() -> {
                    val results = remember(query) { catalog.searchTeams(query) }
                    if (results.isEmpty()) {
                        Text(
                            "No teams match “${query.trim()}”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JessColors.subtle,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn {
                            items(results, key = { it.second.id }) { (league, team) ->
                                PickRow(team, league.id, league.name, isSelected(team)) { toggle(team) }
                            }
                        }
                    }
                }
                openLeagueId == null -> {
                    val core = catalog.leagues.filter { it.id in ONBOARDING_CORE_LEAGUES }
                    val more = catalog.leagues.filterNot { it.id in ONBOARDING_CORE_LEAGUES }
                    LazyColumn {
                        items(core, key = { it.id }) { league ->
                            BrowseRow(league.id, league.name) { openLeagueId = league.id }
                        }
                        if (more.isNotEmpty()) {
                            item {
                                BrowseRow(null, if (showMoreLeagues) "Show fewer sports" else "More sports…") {
                                    showMoreLeagues = !showMoreLeagues
                                }
                            }
                        }
                        if (showMoreLeagues) {
                            items(more, key = { it.id }) { league ->
                                BrowseRow(league.id, league.name) { openLeagueId = league.id }
                            }
                        }
                    }
                }
                else -> {
                    val league = catalog.leagues.first { it.id == openLeagueId }
                    LazyColumn {
                        item { BackRow("All leagues  ·  ${league.name}") { openLeagueId = null } }
                        items(catalog.entriesForLeague(league.id), key = { it.id }) { team ->
                            PickRow(team, league.id, league.name, isSelected(team)) { toggle(team) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedRow(selected: List<Team>, onRemove: (Team) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        selected.forEach { team ->
            Row(
                Modifier
                    .clip(PillShape)
                    .background(Blue50, PillShape)
                    .clickable { onRemove(team) }
                    .semantics { contentDescription = "Remove ${team.name}" }
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(team.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = JessColors.accentInk)
                Icon(
                    painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    tint = JessColors.accentInk,
                    modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = 45f },
                )
            }
        }
    }
}

@Composable
private fun PickRow(team: Team, leagueId: String, qualifier: String, selected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamArtwork(team.id, leagueId, EventPresentation.sport(leagueId).glyph, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(team.name, style = MaterialTheme.typography.titleMedium, color = Blueberry)
            Text(qualifier, style = MaterialTheme.typography.labelSmall, color = JessColors.subtle)
        }
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
            if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = Blueberry, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BrowseRow(leagueId: String?, name: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(leagueId?.let { EventPresentation.sport(it).glyph } ?: "➕", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = Blueberry, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = JessColors.subtle, modifier = Modifier.size(18.dp))
    }
}

private val ONBOARDING_CORE_LEAGUES = setOf("nfl", "cfb", "nba", "mlb", "nhl", "epl", "mls")

@Composable
private fun BackRow(label: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onBack).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = "Back to leagues",
            tint = JessColors.accentInk,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f },
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = JessColors.accentInk)
    }
}

// --- Step 2: Tailored prominent disclosure (Play policy surface — meaning verbatim) ---

@Composable
private fun DisclosureStep(
    teamName: String?,
    extraTeams: Int,
    listenerEnabled: Boolean,
    onGrantAccess: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val who = when {
        teamName == null -> "your teams'"
        extraTeams > 0 -> "$teamName and your other teams'"
        else -> "$teamName"
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Eyebrow("Notification access")
        Text(
            "To hide $who spoilers, Spoiler Alert needs notification access.",
            style = MaterialTheme.typography.headlineMedium,
            color = Blueberry,
        )
        JessCard {
            Eyebrow("Why this access", small = true)
            Text(
                "With it, the app reads the text of notifications from your other apps (sports " +
                    "apps, news, and messages) on this device, and hides ones that match events you are actively hiding.",
                style = MaterialTheme.typography.bodySmall,
                color = Blueberry,
            )
        }
        JessCard {
            Eyebrow("What stays private", small = true)
            Text(
                "All matching happens on your phone. The app connects to the internet only to " +
                    "download public schedules, check when an active event ends, and answer game-status questions you ask. Your notifications — and everything the app " +
                    "hides — never leave this device. Hidden notifications are kept privately on " +
                    "this phone and are deleted within 1 year after you reveal them. " +
                    "You can clear revealed history sooner in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = Blueberry,
            )
        }
        if (!listenerEnabled) {
            PrimaryPill(
                text = "Grant notification access",
                onClick = onGrantAccess,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "You'll be taken to system settings. Enable \"Spoiler Alert\", then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = JessColors.subtle,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_check), null, tint = JessColors.accentInk, modifier = Modifier.size(18.dp))
                Text(
                    "Notification access granted.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = JessColors.accentInk,
                )
            }
            PrimaryPill(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
    }
}

// --- Step 3 (conditional): battery / keep protection running ---

@Composable
private fun BatteryStep(
    ready: Boolean,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Eyebrow("Keep hiding reliable")
        Text(
            "Stop your phone from pausing Spoiler Alert",
            style = MaterialTheme.typography.headlineMedium,
            color = Blueberry,
        )
        JessCard {
            Text(
                "Some phones (especially Samsung) pause background apps to save battery, which " +
                    "would let spoilers through. Excluding Spoiler Alert from battery optimization " +
                    "keeps hiding reliable.",
                style = MaterialTheme.typography.bodySmall,
                color = Blueberry,
            )
        }
        if (ready) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_check), null, tint = JessColors.accentInk, modifier = Modifier.size(18.dp))
                Text(
                    "Background hiding is ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = JessColors.accentInk,
                )
            }
            PrimaryPill(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryPill(text = "Open battery settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
            TextAction("Skip for now", onClick = onContinue)
        }
        Spacer(Modifier.height(8.dp))
    }
}

// --- Step 4: live protection demo ---

@Composable
private fun DemoStep(
    teamName: String?,
    phase: DemoPhase,
    onRun: () -> Unit,
    onReveal: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Eyebrow("See it work")
        Text(
            "Watch Spoiler Alert catch a spoiler",
            style = MaterialTheme.typography.headlineMedium,
            color = Blueberry,
        )
        Text(
            "We'll post a pretend score alert about ${teamName ?: "your team"} — the same way " +
                "a sports app would — and hide it right in front of you. Nothing real is touched.",
            style = MaterialTheme.typography.bodyMedium,
            color = JessColors.subtle,
        )

        when (phase) {
            DemoPhase.Idle -> {
                PrimaryPill(text = "Show me", onClick = onRun, modifier = Modifier.fillMaxWidth())
                TextAction("Skip: take me to Home", onClick = onFinish)
            }
            DemoPhase.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = WoltBlue, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Text("Posting a test spoiler…", style = MaterialTheme.typography.bodyMedium, color = Blueberry)
                }
            }
            is DemoPhase.Hidden -> {
                HiddenPreviewCard(sourceLabel = phase.sourceLabel, revealed = null)
                Text(
                    "Hidden. That alert never reached you. Tap reveal to see what it said.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Blueberry,
                    fontWeight = FontWeight.Bold,
                )
                PrimaryPill(text = "Reveal", onClick = onReveal, modifier = Modifier.fillMaxWidth())
                TextAction("Finish setup", onClick = onFinish)
            }
            is DemoPhase.Revealed -> {
                HiddenPreviewCard(sourceLabel = null, revealed = phase.title to phase.text)
                Text(
                    "That's the whole loop: hidden until you're ready, revealed the moment you ask.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Blueberry,
                    fontWeight = FontWeight.Bold,
                )
                PrimaryPill(text = "Finish setup", onClick = onFinish, modifier = Modifier.fillMaxWidth())
            }
            DemoPhase.Failed -> {
                JessCard {
                    Text(
                        "Couldn't run the check just now — that's fine, hiding is already on. " +
                            "You can try it anytime from Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Blueberry,
                    )
                }
                GhostPill(text = "Try again", onClick = onRun, compact = false, modifier = Modifier.fillMaxWidth())
                PrimaryPill(text = "Finish setup", onClick = onFinish, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The in-onboarding vault preview: a content-free "message hidden" row, or the revealed text. */
@Composable
private fun HiddenPreviewCard(sourceLabel: String?, revealed: Pair<String, String>?) {
    JessCard {
        if (revealed == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).clip(PillShape).background(Blue50), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_lock), null, tint = JessColors.accentInk, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Content hidden", style = MaterialTheme.typography.titleMedium, color = Blueberry)
                    Text("from ${sourceLabel ?: "a sports app"}", style = MaterialTheme.typography.labelSmall, color = JessColors.subtle)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).clip(PillShape).background(Blue50), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_shield), null, tint = JessColors.accentInk, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(revealed.first, style = MaterialTheme.typography.titleMedium, color = Blueberry)
                    Text(revealed.second, style = MaterialTheme.typography.bodySmall, color = Blueberry)
                }
            }
        }
    }
}
