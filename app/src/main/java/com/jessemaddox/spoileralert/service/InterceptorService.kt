package com.jessemaddox.spoileralert.service

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jessemaddox.spoileralert.SpoilerAlertApp
import com.jessemaddox.spoileralert.data.CaptureContext
import com.jessemaddox.spoileralert.data.GameTimeProtection
import com.jessemaddox.spoileralert.data.ProtectionSnapshot
import com.jessemaddox.spoileralert.data.protectionSnapshot
import com.jessemaddox.spoileralert.data.notificationMatchText
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.data.VaultRetention
import com.jessemaddox.spoileralert.domain.ArmedShield
import com.jessemaddox.spoileralert.domain.FantasyPolicy
import com.jessemaddox.spoileralert.domain.MatchMode
import com.jessemaddox.spoileralert.domain.Matcher
import com.jessemaddox.spoileralert.domain.SourcePolicy
import com.jessemaddox.spoileralert.ui.AppPrefs
import com.jessemaddox.spoileralert.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow

/** Process-local signal from a committed arm operation to the already-bound listener. If the
 * listener is not alive, [InterceptorService.onListenerConnected] performs the same sweep later. */
internal object ActiveNotificationSweep {
    val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun request() { requests.tryEmit(Unit) }
}

class InterceptorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: ShieldRepository

    /** Armed shields with expiry, kept hot so onNotificationPosted never touches the DB. */
    @Volatile
    private var protectionCache = ProtectionSnapshot(emptyList(), null)

    /** Opted-in shields' game windows, kept hot for the same reason. */
    @Volatile
    private var gameWindows: List<GameTimeProtection.Window> = emptyList()

    override fun onCreate() {
        super.onCreate()
        repo = ShieldRepository(applicationContext)
        scope.launch {
            // prime SharedPreferences off the main thread
            AppPrefs.excludedPackages(applicationContext)
            (application as SpoilerAlertApp).startupReady.await()
            repo.shieldDao.observeProtectionRecords().collect { records ->
                protectionCache = records.protectionSnapshot(System.currentTimeMillis())
            }
        }
        scope.launch {
            (application as SpoilerAlertApp).startupReady.await()
            repo.observeGameWindows().collect { gameWindows = it }
        }
        scope.launch {
            ActiveNotificationSweep.requests.collect { sweepActiveNotifications() }
        }
    }

    override fun onListenerConnected() {
        AppPrefs.setListenerConnected(applicationContext, true)
        SummaryNotifier.clearWarning(applicationContext)
        scope.launch {
            sweepActiveNotifications()
            repo.vaultDao.pruneRevealedBefore(VaultRetention.cutoff(System.currentTimeMillis()))
        }
    }

    /** Re-query before every sweep. Room Flow delivery is asynchronous, so trusting [armedCache]
     * here could miss the shield that was committed immediately before the request. */
    private suspend fun sweepActiveNotifications() {
        (application as SpoilerAlertApp).startupReady.await()
        protectionCache = repo.sessions.snapshot()
        val rankingMap = runCatching { currentRanking }.getOrNull()
        activeNotifications.orEmpty().forEach { sbn ->
            runCatching { handleSbn(sbn, rankingFor(sbn.key, rankingMap)) }
                .onFailure { Log.e(TAG, "sweep failed for ${sbn.packageName}", it) }
        }
    }

    override fun onListenerDisconnected() {
        AppPrefs.setListenerConnected(applicationContext, false)
        SummaryNotifier.warnListenerDown(applicationContext)
        requestRebind(ComponentName(this, InterceptorService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handlePosted(sbn, null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        handlePosted(sbn, rankingFor(sbn.key, rankingMap))
    }

    private fun handlePosted(sbn: StatusBarNotification, ranking: Ranking?) {
        // One malformed foreign notification must not kill protection for all the rest.
        try {
            handleSbn(sbn, ranking)
        } catch (t: Throwable) {
            Log.e(TAG, "failed handling notification from ${sbn.packageName}", t)
        }
    }

    private fun handleSbn(sbn: StatusBarNotification, ranking: Ranking?) {
        // Our own notifications are never processed — except debug fakes, which exist to be caught.
        val isDebugFake = sbn.packageName == packageName &&
            sbn.notification.channelId == SpoilerAlertApp.CHANNEL_DEBUG_FAKE
        if (sbn.packageName == packageName && !isDebugFake) return

        // Calls, missed calls, alarms, and navigation are time-sensitive. A team name in one of
        // these surfaces must never cause Spoiler Alert to swallow the interruption.
        if (NotificationSafety.shouldAlwaysPass(sbn.notification.category)) {
            trace(TraceLines.safetySkip(sbn.packageName, sbn.notification.category))
            return
        }

        val now = System.currentTimeMillis()
        val snapshot = protectionCache
        val active = snapshot.protections.filter { it.expiresAtMillis > now }
        // A game window that is open by the clock protects now, even if Android has not yet run
        // its start worker. Hide for it immediately and start its session in the background.
        val due = GameTimeProtection.dueWindows(
            gameWindows, snapshot.gameTimeShields.mapValues { it.value.stoppedAtMillis },
            active.mapTo(mutableSetOf()) { it.token.shieldId }, now,
        )
        val starting = if (due.isEmpty()) null else scope.async {
            due.forEach { runCatching { repo.startGameWindow(it.shieldId, it.eventId) } }
        }
        val dueShields = due.mapNotNull { snapshot.gameTimeShields[it.shieldId]?.shield }.distinctBy { it.id }
        val shields = active.map { it.shield } + dueShields
        // Trace decisions for diagnosable sources so field failures leave evidence (Debug tools).
        val traceworthy = isDebugFake || sbn.packageName in SourcePolicy.MESSAGING ||
            sbn.packageName in SourcePolicy.SPORTS_NEWS || sbn.hasMessagingStyle()

        // Ordinary classification is retained even for a package-wide fantasy capture.
        val mode = when {
            isDebugFake -> MatchMode.AGGRESSIVE
            else -> {
                val base = SourcePolicy.modeFor(sbn.packageName, AppPrefs.excludedPackages(applicationContext))
                if (base == MatchMode.AGGRESSIVE && sbn.hasMessagingStyle()) MatchMode.STRICT else base
            }
        }
        if (sbn.packageName != packageName && FantasyPolicy.shouldHide(
                sbn.packageName, AppPrefs.fantasyPackages(applicationContext),
                AppPrefs.excludedPackages(applicationContext), shields.isNotEmpty())) {
            val texts = sbn.extractTexts()
            val metadata = sbn.captureMetadata(ranking)
            if (active.isEmpty()) {
                // Only a due window covers this moment: its session (and fantasy epoch) starts first.
                trace(TraceLines.fantasyHide(sbn.packageName))
                cancelNotification(sbn.key)
                cancelLoneGroupSummary(sbn)
                val recovery = dueShields.first().id.let { snapshot.gameTimeShields.getValue(it) }
                scope.launch {
                    starting?.await()
                    val capture = repo.sessions.snapshot().fantasy
                        ?.copy(matchMode = mode.name, capturedAtMillis = now)
                        ?: recovery.recoveryCapture(mode, now).also { Log.w(TAG, "game window did not start; holding fantasy item sealed") }
                    hideAndVault(sbn, capture, texts, metadata, groupWithEvent = false)
                }
                return
            }
            val capture = snapshot.fantasy?.copy(matchMode = mode.name, capturedAtMillis = now) ?: return
            trace(TraceLines.fantasyHide(sbn.packageName))
            cancelNotification(sbn.key)
            cancelLoneGroupSummary(sbn)
            scope.launch { hideAndVault(sbn, capture, texts, metadata, groupWithEvent = false) }
            return
        }
        if (shields.isEmpty()) {
            if (traceworthy) trace(TraceLines.emptyCache(sbn.packageName))
            return
        }
        if (mode == MatchMode.OFF) {
            if (traceworthy) trace(TraceLines.modeOff(sbn.packageName))
            return
        }

        val texts = sbn.extractTexts()
        val metadata = sbn.captureMetadata(ranking)
        if (traceworthy) {
            trace(
                TraceLines.capabilities(
                    pkg = sbn.packageName,
                    conversation = metadata.isConversation,
                    priority = metadata.isImportantConversation,
                    messages = metadata.messageCount,
                    open = metadata.hasExactOpen,
                    reply = metadata.hasReplyAction,
                    markUnread = metadata.hasMarkUnreadAction,
                )
            )
        }
        val hit = Matcher.match(notificationMatchText(texts.title, texts.conversation, texts.body), shields, mode)
        if (hit == null) {
            if (traceworthy) trace(TraceLines.noMatch(sbn.packageName, mode, shields.size))
            return
        }
        if (traceworthy) trace(TraceLines.matched(sbn.packageName, mode, hit.name))

        val running = active.firstOrNull { it.shield.id == hit.id }
        cancelNotification(sbn.key)
        cancelLoneGroupSummary(sbn)

        if (running != null) {
            val capture = running.capture(mode, now)
            scope.launch { hideAndVault(sbn, capture, texts, metadata) }
            return
        }
        // Matched a due game window: store it under the session its start just created.
        val recovery = snapshot.gameTimeShields.getValue(hit.id)
        scope.launch {
            starting?.await()
            val capture = repo.sessions.snapshot().protections
                .firstOrNull { it.token.shieldId == hit.id }?.capture(mode, now)
                ?: recovery.recoveryCapture(mode, now).also { Log.w(TAG, "game window for shield ${hit.id} did not start; holding item sealed") }
            hideAndVault(sbn, capture, texts, metadata)
        }
    }

    /** Shared tail of every hide: vault the notification's metadata/content under [shieldId],
     *  refresh the summary/widget, post its content-free caught-item stand-in, then verify
     *  the cancel stuck. Must run inside [scope] — [ShieldRepository] calls here are suspend. */
    private suspend fun hideAndVault(
        sbn: StatusBarNotification,
        capture: CaptureContext,
        texts: ExtractedTexts,
        metadata: NotificationCaptureMetadata,
        groupWithEvent: Boolean = true,
    ) {
        val label = appLabel(sbn.packageName)
        val rowId = repo.upsertVaultHidden(
            key = sbn.key,
            shieldId = capture.shieldId,
            sourcePackage = sbn.packageName,
            sourceAppLabel = label,
            title = texts.title,
            text = texts.body,
            postedAt = sbn.postTime,
            conversation = texts.conversation,
            messageCount = metadata.messageCount,
            isConversation = metadata.isConversation,
            isImportantConversation = metadata.isImportantConversation,
            sourceCategory = metadata.sourceCategory,
            hasExactOpen = metadata.hasExactOpen,
            hasReplyAction = metadata.hasReplyAction,
            hasMarkUnreadAction = metadata.hasMarkUnreadAction,
            capture = capture,
            afterPersist = { stored ->
                if (stored.revealedAtMillis == null) MaskedNotifier.post(
                    applicationContext, stored.id, stored.shieldId, label, texts.conversation,
                    repo.shieldDao.byId(stored.shieldId)?.kind != "FANTASY",
                    sourceContentIntent = sbn.notification.contentIntent,
                )
            },
        )
        SummaryNotifier.refresh(applicationContext)
        // Widget shows metadata only (vaulted yes/no) — keep it current.
        WidgetRefresher.refreshNow(applicationContext)
        delay(500)
        if (activeNotifications.orEmpty().any { it.key == sbn.key }) {
            AppPrefs.recordCancelMiss(applicationContext, sbn.packageName)
        }
    }

    /** If cancelling this child leaves only the group summary, cancel the summary too. */
    private fun cancelLoneGroupSummary(cancelled: StatusBarNotification) {
        val group = cancelled.notification.group ?: return
        val siblings = activeNotifications.orEmpty().filter {
            it.packageName == cancelled.packageName &&
                it.notification.group == group && it.key != cancelled.key
        }
        val summaryOnly = siblings.size == 1 &&
            siblings[0].notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (summaryOnly) cancelNotification(siblings[0].key)
    }

    private fun trace(line: String) {
        AppPrefs.recordTrace(applicationContext, line)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun rankingFor(key: String, rankingMap: RankingMap?): Ranking? {
        rankingMap ?: return null
        val ranking = Ranking()
        return ranking.takeIf { rankingMap.getRanking(key, it) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SpoilerAlert"
    }
}
