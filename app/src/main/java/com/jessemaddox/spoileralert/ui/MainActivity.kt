package com.jessemaddox.spoileralert.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import com.jessemaddox.spoileralert.data.RevealSelection
import com.jessemaddox.spoileralert.service.SessionActions
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jessemaddox.spoileralert.R
import com.jessemaddox.spoileralert.data.GameShieldLifecycle
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.data.WaitingSummary
import com.jessemaddox.spoileralert.ui.theme.Blue50
import com.jessemaddox.spoileralert.ui.theme.Blueberry
import com.jessemaddox.spoileralert.ui.theme.JessColors
import com.jessemaddox.spoileralert.ui.theme.PillShape
import com.jessemaddox.spoileralert.ui.theme.SpoilerTheme
import com.jessemaddox.spoileralert.ui.theme.TextAction
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var vaultRequestTick by mutableStateOf(0)
    private var vaultItemRequest by mutableStateOf<VaultItemRequest?>(null)
    private var gameStatusShieldRequest by mutableStateOf<Long?>(null)
    private var stopProtectionRequest by mutableStateOf<Pair<Long, RevealSelection>?>(null)
    private var homeRequestTick by mutableStateOf(0)
    private var historyRequestTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // Re-read the trusted deep link after configuration/process recreation too. Reveal is
        // idempotent, and retaining the screen is less surprising than dropping back to Home.
        captureNotificationIntent(intent)
        if (AppPrefs.isOnboarded(this)) requestPostNotificationsIfNeeded()
        setContent {
            SpoilerTheme {
                var onboarded by rememberSaveable { mutableStateOf(AppPrefs.isOnboarded(this)) }
                val status by vm.status.collectAsStateWithLifecycle()
                val demoPhase by vm.demoPhase.collectAsStateWithLifecycle()
                var initialized by remember { mutableStateOf(false) }
                var startupFailed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    runCatching { (application as com.jessemaddox.spoileralert.SpoilerAlertApp).startupReady.await() }
                        .onSuccess { initialized = true }.onFailure { startupFailed = true }
                }
                if (!initialized) {
                    Surface { Text(if (startupFailed) "Could not restore hiding. Close and reopen the app."
                        else "Restoring your saved sessions…", modifier = Modifier.safeDrawingPadding().padding(24.dp)) }
                } else if (!onboarded) {
                    OnboardingScreen(
                        catalog = vm.catalog,
                        listenerEnabled = status.listenerEnabled,
                        demoPhase = demoPhase,
                        onAddTeams = { teams -> teams.forEach(vm::addTeamShield) },
                        onGrantAccess = { openListenerSettings() },
                        onRequestNotifications = { requestPostNotificationsIfNeeded() },
                        onRunDemo = { teamName -> vm.runProtectionDemo(teamName) },
                        onRevealDemo = { vm.revealProtectionDemo() },
                        onDemoTeardown = { vm.teardownDemo() },
                        onFinish = {
                            vm.finishOnboarding { onboarded = true }
                        },
                    )
                } else {
                    MainScaffold()
                }
            }
        }
    }

    @Composable
    private fun MainScaffold() {
        val browserState = rememberSaveableStateHolder()
        var tab by rememberSaveable { mutableStateOf(0) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showInterests by rememberSaveable { mutableStateOf(false) }
        var showGames by rememberSaveable { mutableStateOf(false) }
        var gamesTodayOnly by rememberSaveable { mutableStateOf(false) }
        var showHistory by rememberSaveable { mutableStateOf(false) }
        var catchUpSinceMillis by rememberSaveable { mutableStateOf<Long?>(null) }
        var statusGame by rememberSaveable { mutableStateOf<ProtectedLiveGame?>(null) }
        LaunchedEffect(vaultItemRequest) {
            val request = vaultItemRequest ?: return@LaunchedEffect
            if (request.revealOnOpen) vm.revealItem(request.rowId)
            showSettings = false
            showInterests = false
            showGames = false
            showHistory = false
            statusGame = null
        }
        LaunchedEffect(vaultRequestTick) {
            if (vaultRequestTick > 0) {
                tab = 1
                showSettings = false
                showInterests = false
                showGames = false
                showHistory = false
                statusGame = null
            }
        }
        LaunchedEffect(homeRequestTick) {
            if (homeRequestTick > 0) {
                tab = 0
                showSettings = false
                showInterests = false
                showGames = false
                showHistory = false
                statusGame = null
                vaultItemRequest = null
            }
        }
        LaunchedEffect(historyRequestTick) {
            if (historyRequestTick > 0) {
                tab = 0
                showSettings = false
                showInterests = false
                showGames = false
                showHistory = true
                catchUpSinceMillis = null
                statusGame = null
                vaultItemRequest = null
            }
        }
        LaunchedEffect(stopProtectionRequest) {
            if (stopProtectionRequest != null) {
                tab = 0
                showSettings = false
                showInterests = false
                showGames = false
                showHistory = false
                statusGame = null
                vaultItemRequest = null
            }
        }
        var showAdd by remember { mutableStateOf(false) }
        val protectionUi by vm.protectionUi.collectAsStateWithLifecycle()
        val shields = protectionUi.shields
        val actionSelections = protectionUi.actions
        val hidden by vm.hiddenItems.collectAsStateWithLifecycle(emptyList())
        val allVault by vm.allVaultItems.collectAsStateWithLifecycle(emptyList())
        val nextGames by vm.nextGames.collectAsStateWithLifecycle(emptyMap())
        val sessionGames by vm.sessionGames.collectAsStateWithLifecycle(emptyMap())
        val dailyFeed by vm.dailyFeed.collectAsStateWithLifecycle(
            DailyEventFeed(emptyList(), emptyList(), emptyList()),
        )
        val protectedGames by vm.protectedGames.collectAsStateWithLifecycle(emptyList())
        val scoreChecks by vm.scoreChecks.collectAsStateWithLifecycle()
        val gameTimelines by vm.gameTimelines.collectAsStateWithLifecycle()
        val catchUpChecks by vm.catchUpChecks.collectAsStateWithLifecycle()
        val discoverableGames by vm.discoverableGames.collectAsStateWithLifecycle(emptyList())
        val scheduleStatus by vm.scheduleStatus.collectAsStateWithLifecycle("Saved schedules · no scores")
        val leagueGamesById by vm.leagueGamesById.collectAsStateWithLifecycle(emptyMap())
        val protectionStatus by vm.status.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(gameStatusShieldRequest, protectedGames, sessionGames, shields) {
            val shieldId = gameStatusShieldRequest ?: return@LaunchedEffect
            val eventIds = sessionGames[shieldId].orEmpty().mapTo(mutableSetOf()) { it.id }
            shields.firstOrNull { it.id == shieldId }?.gameEventId?.let(eventIds::add)
            protectedGames.firstOrNull { it.eventId in eventIds }?.let {
                statusGame = it
                gameStatusShieldRequest = null
            }
        }

        LaunchedEffect(Unit) { vm.userMessages.collect { snackbar.showSnackbar(it) } }

        if (showSettings) BackHandler { showSettings = false }
        if (showInterests) BackHandler { showInterests = false }
        if (showGames) BackHandler { showGames = false }
        if (showHistory) BackHandler {
            showHistory = false
            catchUpSinceMillis = null
        }
        if (statusGame != null) BackHandler { statusGame = null }
        if (vaultItemRequest != null) BackHandler { vaultItemRequest = null }

        // Spent GAME shields (session over, everything revealed) leave Home; TEAM/CUSTOM,
        // armed, sealed, and protect-later shields stay. See GameShieldLifecycle.
        val hiddenCounts = hidden.groupBy { it.shieldId }
            .mapValues { (_, items) -> WaitingSummary.from(items).notificationCount }
        val totalVaultCounts = allVault.groupingBy { it.shieldId }.eachCount()
        val sportByShieldId = shields.associate { shield ->
            val leagueId = vm.catalog.leagueIdForTeam(shield.catalogTeamId)
                ?: shield.gameEventId?.let { leagueGamesById[it]?.leagueId }
            shield.id to when (shield.kind) {
                "FANTASY" -> SportPresentation("Fantasy sports", "🏆")
                else -> EventPresentation.sport(leagueId)
            }
        }
        val homeShields = shields.filter { s ->
            GameShieldLifecycle.showOnHome(
                kind = s.kind, armed = s.armed,
                hiddenCount = hiddenCounts[s.id] ?: 0,
                totalVaultCount = totalVaultCounts[s.id] ?: 0,
                hasPendingGame = nextGames.containsKey(s.id),
            )
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopBar(
                    wordmark = when {
                        showSettings -> "Settings"
                        showInterests -> "My teams & events"
                        statusGame != null -> "Game questions"
                        showGames -> "Find a game"
                        showHistory && catchUpSinceMillis != null -> "Catch up"
                        showHistory -> "History"
                        vaultItemRequest != null -> "Spoiler"
                        tab == 1 -> "Hidden"
                        else -> "Home"
                    },
                    showSettingsAction = !showSettings && !showInterests && !showGames &&
                        !showHistory && statusGame == null && vaultItemRequest == null,
                    onSettings = { showSettings = true },
                    onDone = {
                        if (statusGame != null) {
                            statusGame = null
                        } else {
                        showSettings = false
                        showInterests = false
                        showGames = false
                        showHistory = false
                        catchUpSinceMillis = null
                        statusGame = null
                        vaultItemRequest = null
                        }
                    },
                )
            },
            bottomBar = {
                if (!showSettings && !showInterests && !showGames && !showHistory &&
                    statusGame == null && vaultItemRequest == null
                ) {
                    JessNavBar(tab = tab, hiddenCount = hidden.size, onSelect = { tab = it })
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Surface(
                Modifier.padding(padding).consumeWindowInsets(padding),
                color = MaterialTheme.colorScheme.background,
            ) {
                when {
                    vaultItemRequest != null -> {
                        val request = vaultItemRequest!!
                        val item = allVault.firstOrNull { it.id == request.rowId }
                        val appLaunch = item?.let {
                            packageManager.getLaunchIntentForPackage(it.sourcePackage)
                        }
                        val canOpenSource = request.sourceContentIntent != null || appLaunch != null
                        NotificationItemScreen(
                            item = item,
                            onReveal = vm::revealItem,
                            onOpenSource = if (canOpenSource) {
                                {
                                    val exactOpened = request.sourceContentIntent?.let { pending ->
                                        runCatching { pending.send(); true }.getOrDefault(false)
                                    } ?: false
                                    if (!exactOpened) appLaunch?.let(::startActivity)
                                }
                            } else null,
                            opensExactConversation = request.sourceContentIntent != null,
                        )
                    }
                    showSettings -> SettingsScreen(
                        onManageInterests = {
                            showSettings = false
                            showInterests = true
                        },
                        onClearHistory = vm::clearRevealedHistory,
                    )
                    showInterests -> ProtectionTeamsScreen(
                        shields = shields,
                        selections = actionSelections,
                        hiddenCounts = hiddenCounts,
                        nextGames = nextGames,
                        catalog = vm.catalog,
                        onProtectCustom = { shield ->
                            vm.armSession(shield.id, AppPrefs.autoDisarmHours(this@MainActivity))
                            scope.launch { snackbar.showSnackbar("Hiding notifications for ${shield.name}") }
                        },
                        onAdd = { showAdd = true },
                        onDelete = vm::deleteShield,
                    )
                    showHistory -> HistoryScreen(
                        items = allVault,
                        shields = shields,
                        sportByShieldId = sportByShieldId,
                        releasedSinceMillis = catchUpSinceMillis,
                    )
                    statusGame != null -> GameStatusScreen(
                        game = statusGame!!,
                        scoreCheck = scoreChecks[statusGame!!.eventId],
                        timelineState = gameTimelines[statusGame!!.eventId],
                        catchUpState = catchUpChecks[statusGame!!.eventId],
                        onCatchUp = { vm.askCatchUp(statusGame!!, it) },
                        onAsk = { question, forceRefresh ->
                            vm.askProtectedScoreQuestion(statusGame!!, question, forceRefresh)
                        },
                        onLoadTimeline = { forceRefresh ->
                            vm.loadGameTimeline(statusGame!!, forceRefresh)
                        },
                        onAskEarlier = { checkpoint, question ->
                            vm.askHistoricalScoreQuestion(statusGame!!, checkpoint, question)
                        },
                        onCheckSkip = { start, end, forceRefresh ->
                            vm.checkSkipAhead(statusGame!!, start, end, forceRefresh)
                        },
                    )
                    showGames -> browserState.SaveableStateProvider("games") { GamesBrowser(
                        games = discoverableGames,
                        leagueNames = vm.leagueNames,
                        catalog = vm.catalog,
                        onProtect = { game ->
                            val hours = SessionDurations.gameAwareDefaultHours(
                                game.leagueId, game.startMillis, System.currentTimeMillis(),
                            )
                            vm.protectGame(game, hours)
                            showGames = false
                            scope.launch { snackbar.showSnackbar("Hiding notifications for ${game.shortName}") }
                        },
                        onProtectLater = vm::protectGameLater,
                        initialTodayOnly = gamesTodayOnly,
                        onOpenGame = { statusGame = ProtectedGames.fromEvent(it) },
                        onRefresh = { vm.refreshSchedules(true) },
                        scheduleStatus = scheduleStatus,
                    ) }
                    tab == 0 -> ProtectionHomeScreen(
                        shields = homeShields,
                        selections = actionSelections,
                        status = protectionStatus,
                        hiddenCounts = hiddenCounts,
                        lastHiddenAtByShield = hidden.groupBy { it.shieldId }
                            .mapValues { (_, items) -> items.maxOf { it.postedAtMillis } },
                        nextGames = nextGames,
                        sessionGames = sessionGames,
                        protectedGames = protectedGames,
                        dailyFeed = dailyFeed,
                        catalog = vm.catalog,
                        onFixListener = { openListenerSettings() },
                        onFixNotifications = { openAppNotificationSettings() },
                        onQuickProtect = { shield, game ->
                            val leagueId = vm.catalog.leagueIdForTeam(shield.catalogTeamId)
                                ?: shield.gameEventId?.let { leagueGamesById[it]?.leagueId }
                            val hours = SessionDurations.gameAwareDefaultHours(
                                leagueId, game?.startMillis, System.currentTimeMillis(),
                            )
                            vm.armSession(shield.id, hours)
                            scope.launch { snackbar.showSnackbar("Hiding notifications for ${game?.shortName ?: shield.name}") }
                        },
                        onEndProtection = { shield, selection ->
                            val releaseStarted = System.currentTimeMillis()
                            vm.endProtection(shield.id, selection) { outcome ->
                                if (outcome != null && outcome.revealedCount > 0) {
                                    catchUpSinceMillis = releaseStarted
                                    showHistory = true
                                }
                                scope.launch {
                                    val summary = outcome?.releasedSummary
                                    val retained = outcome?.retainedSummary?.notificationCount ?: 0
                                    snackbar.showSnackbar(
                                        when {
                                            outcome?.newerSessionStillActive == true -> "Earlier notifications handled. Your newer session is still hiding."
                                            summary != null && summary.totalItemCount > 0 -> "Hiding stopped. ${summary.compactCopy().replaceFirstChar { it.uppercase() }}."
                                            retained > 0 -> "Hiding stopped. $retained notification${if (retained == 1) " is" else "s are"} still covered by another event."
                                            else -> "Hiding stopped. No notifications were hidden."
                                        }
                                    )
                                }
                            }
                        },
                        onOpenHidden = { tab = 1 },
                        onExtend = { shield ->
                            vm.extend(shield)
                        },
                        onOpenGameStatus = { statusGame = it },
                        onProtectAnotherGame = {
                            gamesTodayOnly = true
                            vm.refreshSchedules()
                            showGames = true
                        },
                        onProtectFeatured = { game ->
                            val hours = SessionDurations.gameAwareDefaultHours(
                                game.leagueId, game.startMillis, System.currentTimeMillis(),
                            )
                            vm.protectGame(game, hours)
                            scope.launch { snackbar.showSnackbar("Hiding notifications for ${game.shortName}") }
                        },
                    )
                    tab == 1 -> VaultScreen(
                        items = allVault,
                        shields = shields,
                        sportByShieldId = sportByShieldId,
                        onReveal = vm::revealItem,
                        onDisarmShield = vm::disarm,
                        selections = actionSelections,
                        onOpenHistory = { showHistory = true },
                    )
                    else -> Unit
                }
                if (showAdd) {
                    AddShieldSheet(
                        catalog = vm.catalog,
                        games = discoverableGames,
                        leagueNames = vm.leagueNames,
                        onAddTeam = { team ->
                            vm.addTeamShield(team)
                            showAdd = false
                            tab = 0
                        },
                        onProtectGame = { game ->
                            showAdd = false
                            val hours = SessionDurations.gameAwareDefaultHours(
                                game.leagueId, game.startMillis, System.currentTimeMillis(),
                            )
                            vm.protectGame(game, hours)
                            scope.launch { snackbar.showSnackbar("Hiding notifications for ${game.shortName}") }
                        },
                        onProtectGameLater = { showAdd = false; vm.protectGameLater(it) },
                        onOpenGame = {
                            showAdd = false
                            showInterests = false
                            showSettings = false
                            gamesTodayOnly = true
                            showGames = true
                            statusGame = ProtectedGames.fromEvent(it)
                        },
                        onRefresh = { vm.refreshSchedules(true) },
                        scheduleStatus = scheduleStatus,
                        onAddCustom = vm::addCustomShield,
                        onDismiss = { showAdd = false },
                    )
                }
            }
        }
        val stopShield = shields.firstOrNull { it.id == stopProtectionRequest?.first }
        if (stopShield != null) {
            AlertDialog(
                onDismissRequest = { stopProtectionRequest = null },
                title = { Text("Stop hiding notifications for ${stopShield.name}?") },
                text = {
                    Text(
                        "This stops hiding and reveals ${hiddenCounts[stopShield.id] ?: 0} notification${if ((hiddenCounts[stopShield.id] ?: 0) == 1) "" else "s"} so you can catch up. Anything also covered by another active event stays hidden."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val releaseStarted = System.currentTimeMillis()
                        vm.endProtection(stopShield.id, stopProtectionRequest!!.second) { outcome ->
                            if (outcome != null && outcome.revealedCount > 0) {
                                catchUpSinceMillis = releaseStarted
                                showHistory = true
                            }
                            scope.launch {
                                val summary = outcome?.releasedSummary
                                val retained = outcome?.retainedSummary?.notificationCount ?: 0
                                snackbar.showSnackbar(
                                    when {
                                        outcome?.newerSessionStillActive == true -> "Earlier notifications handled. Your newer session is still hiding."
                                        summary != null && summary.totalItemCount > 0 -> "Hiding stopped. ${summary.compactCopy().replaceFirstChar { it.uppercase() }}."
                                        retained > 0 -> "Hiding stopped. $retained notification${if (retained == 1) " is" else "s are"} still covered by another event."
                                        else -> "Hiding stopped. No notifications were hidden."
                                    }
                                )
                            }
                        }
                        stopProtectionRequest = null
                    }) {
                        val count = hiddenCounts[stopShield.id] ?: 0
                        Text(
                            if (count > 0) "Stop & reveal $count notification${if (count == 1) "" else "s"}"
                            else "Stop hiding"
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stopProtectionRequest = null }) { Text("Keep hiding") }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationIntent(intent)
    }

    private fun captureNotificationIntent(intent: Intent) {
        val rowId = intent.getLongExtra(EXTRA_VAULT_ROW_ID, -1L)
        val trustedAction = AppPrefs.isValidNotificationActionToken(
            this,
            intent.getStringExtra(EXTRA_ACTION_TOKEN),
        )
        gameStatusShieldRequest = intent.getLongExtra(EXTRA_SHIELD_ID, -1L)
            .takeIf { it >= 0 && trustedAction && intent.action == ACTION_OPEN_GAME_STATUS }
        stopProtectionRequest = if (trustedAction && intent.action == ACTION_CONFIRM_STOP_PROTECTION) {
            SessionActions.selection(intent)?.let { selection ->
                SessionActions.token(intent)!!.shieldId to selection
            }
        } else null
        vaultItemRequest = when {
            rowId >= 0 && trustedAction && intent.action == ACTION_REVEAL_HIDDEN_ITEM ->
                VaultItemRequest(rowId, true, intent.sourceContentIntent())
            rowId >= 0 && trustedAction && intent.action == ACTION_OPEN_HIDDEN_ITEM ->
                VaultItemRequest(rowId, false, intent.sourceContentIntent())
            else -> null
        }
        if (vaultItemRequest == null && intent.getBooleanExtra("openVault", false)) vaultRequestTick++
        if (intent.action == ACTION_OPEN_HOME) homeRequestTick++
        if (intent.action == ACTION_OPEN_HISTORY && trustedAction) historyRequestTick++
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStatus()
        vm.refreshSchedules()
    }

    private fun requestPostNotificationsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    fun openListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAppNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    companion object {
        const val ACTION_OPEN_HIDDEN_ITEM = "com.jessemaddox.spoileralert.OPEN_HIDDEN_ITEM"
        const val ACTION_REVEAL_HIDDEN_ITEM = "com.jessemaddox.spoileralert.REVEAL_HIDDEN_ITEM"
        const val ACTION_OPEN_GAME_STATUS = "com.jessemaddox.spoileralert.OPEN_GAME_STATUS"
        const val ACTION_OPEN_HOME = "com.jessemaddox.spoileralert.OPEN_HOME"
        const val ACTION_OPEN_HISTORY = "com.jessemaddox.spoileralert.OPEN_HISTORY"
        const val ACTION_CONFIRM_STOP_PROTECTION =
            "com.jessemaddox.spoileralert.CONFIRM_STOP_PROTECTION"
        const val EXTRA_VAULT_ROW_ID = "vaultRowId"
        const val EXTRA_SHIELD_ID = "shieldId"
        const val EXTRA_ACTION_TOKEN = "notificationActionToken"
        const val EXTRA_SOURCE_CONTENT_INTENT = "sourceContentIntent"
    }
}

private data class VaultItemRequest(
    val rowId: Long,
    val revealOnOpen: Boolean,
    val sourceContentIntent: PendingIntent? = null,
)

@Suppress("DEPRECATION")
private fun Intent.sourceContentIntent(): PendingIntent? =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(MainActivity.EXTRA_SOURCE_CONTENT_INTENT, PendingIntent::class.java)
    } else {
        getParcelableExtra(MainActivity.EXTRA_SOURCE_CONTENT_INTENT)
    }

/** Wordmark top bar: native back affordance on secondary screens, settings on daily tabs. */
@Composable
private fun TopBar(
    wordmark: String,
    showSettingsAction: Boolean,
    onSettings: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSettingsAction) {
            Text(
                wordmark.uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                color = Blueberry,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = "Settings",
                    tint = JessColors.subtle,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            IconButton(onClick = onDone) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    tint = Blueberry,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                wordmark.uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                color = Blueberry,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Two daily destinations. Infrequent interest maintenance lives in Settings. */
@Composable
private fun JessNavBar(tab: Int, hiddenCount: Int, onSelect: (Int) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(JessColors.hairline))
        Row(
            Modifier.fillMaxWidth().height(72.dp).background(Color.White),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                label = "Home", iconRes = R.drawable.ic_shield,
                active = tab == 0, badge = null,
                modifier = Modifier.weight(1f), onClick = { onSelect(0) },
            )
            NavItem(
                label = "Hidden", iconRes = R.drawable.ic_lock,
                active = tab == 1, badge = hiddenCount.takeIf { it > 0 },
                modifier = Modifier.weight(1f), onClick = { onSelect(1) },
            )
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    iconRes: Int,
    active: Boolean,
    badge: Int?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.selectable(selected = active, onClick = onClick), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .background(if (active) Blue50 else Color.Transparent, PillShape)
                    .padding(horizontal = 18.dp, vertical = 3.dp),
            ) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = null,
                    tint = if (active) JessColors.accentInk else JessColors.subtle,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) Blueberry else JessColors.subtle,
            )
        }
        if (badge != null) {
            Text(
                "$badge",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = 26.dp, y = 8.dp)
                    .background(Blueberry, PillShape)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}
