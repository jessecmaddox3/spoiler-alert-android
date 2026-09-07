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
import com.jessemaddox.spoileralert.data.ShieldCodec
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
    private var armedCache: List<Pair<ArmedShield, Long>> = emptyList()

    override fun onCreate() {
        super.onCreate()
        repo = ShieldRepository(applicationContext)
        scope.launch {
            // prime SharedPreferences off the main thread
            AppPrefs.excludedPackages(applicationContext)
            repo.shieldDao.observeArmed().collect { entities ->
                armedCache = entities.toCache()
            }
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
        armedCache = repo.shieldDao.armed().toCache()
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
        val shields = armedCache.filter { it.second > now }.map { it.first }
        // Trace decisions for diagnosable sources so field failures leave evidence (Debug tools).
        val traceworthy = isDebugFake || sbn.packageName in SourcePolicy.MESSAGING ||
            sbn.packageName in SourcePolicy.SPORTS_NEWS || sbn.hasMessagingStyle()

        // Fantasy-app shields (v3 amendment item 3): a package-level rule that runs BEFORE
        // normal keyword matching. If the source is a user-designated fantasy app and any
        // session is active, hide EVERY notification from it — no player-name matching yet, so
        // the only safe move is to hide the whole app's traffic while protection is on. Own
        // package (including debug fakes, which exist to exercise normal matching) never
        // qualifies. Vault rows are attributed to the fantasy sentinel shield, not any real
        // team shield — see [ShieldRepository.fantasyShieldId].
        if (sbn.packageName != packageName) {
            val fantasyHit = FantasyPolicy.shouldHide(
                pkg = sbn.packageName,
                fantasyPackages = AppPrefs.fantasyPackages(applicationContext),
                excludedPackages = AppPrefs.excludedPackages(applicationContext),
                anySessionActive = shields.isNotEmpty(),
            )
            if (fantasyHit) {
                trace(TraceLines.fantasyHide(sbn.packageName))
                cancelNotification(sbn.key)
                cancelLoneGroupSummary(sbn)
                val texts = sbn.extractTexts()
                val metadata = sbn.captureMetadata(ranking)
                scope.launch {
                    hideAndVault(
                        sbn, repo.fantasyShieldId(), texts, metadata, groupWithEvent = false,
                    )
                }
                return
            }
        }

        if (shields.isEmpty()) {
            if (traceworthy) trace(TraceLines.emptyCache(sbn.packageName))
            return
        }

        val mode = when {
            isDebugFake -> MatchMode.AGGRESSIVE
            else -> {
                val base = SourcePolicy.modeFor(sbn.packageName, AppPrefs.excludedPackages(applicationContext))
                // Unknown chat apps still deserve strict treatment; exclusions (OFF) always win.
                if (base == MatchMode.AGGRESSIVE && sbn.hasMessagingStyle()) MatchMode.STRICT else base
            }
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
        val hit = Matcher.match(texts.combined, shields, mode)
        if (hit == null) {
            if (traceworthy) trace(TraceLines.noMatch(sbn.packageName, mode, shields.size))
            return
        }
        if (traceworthy) trace(TraceLines.matched(sbn.packageName, mode, hit.name))

        cancelNotification(sbn.key)
        cancelLoneGroupSummary(sbn)

        scope.launch { hideAndVault(sbn, hit.id, texts, metadata) }
    }

    /** Shared tail of every hide: vault the notification's metadata/content under [shieldId],
     *  refresh the summary/widget, post its content-free caught-item stand-in, then verify
     *  the cancel stuck. Must run inside [scope] — [ShieldRepository] calls here are suspend. */
    private suspend fun hideAndVault(
        sbn: StatusBarNotification,
        shieldId: Long,
        texts: ExtractedTexts,
        metadata: NotificationCaptureMetadata,
        groupWithEvent: Boolean = true,
    ) {
        val label = appLabel(sbn.packageName)
        val rowId = repo.upsertVaultHidden(
            key = sbn.key,
            shieldId = shieldId,
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
        )
        SummaryNotifier.refresh(applicationContext)
        // Widget shows metadata only (vaulted yes/no) — keep it current.
        WidgetRefresher.refreshNow(applicationContext)
        val storedRow = rowId?.let { repo.vaultDao.byId(it) }
        if (storedRow != null && storedRow.revealedAtMillis == null) {
            MaskedNotifier.post(
                applicationContext, rowId, storedRow.shieldId, label, texts.conversation,
                groupWithEvent, sourceContentIntent = sbn.notification.contentIntent,
            )
            // A reveal may have raced us between the upsert and the post; verify and retract.
            if (repo.vaultDao.byId(rowId)?.revealedAtMillis != null) {
                MaskedNotifier.cancel(applicationContext, rowId)
            }
        }
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

    private fun List<com.jessemaddox.spoileralert.data.ShieldEntity>.toCache(): List<Pair<ArmedShield, Long>> =
        mapNotNull { e ->
            val expiresAt = ShieldCodec.expiresAtMillis(e) ?: return@mapNotNull null
            runCatching { ShieldCodec.toArmedShield(e) to expiresAt }
                .onFailure { Log.w(TAG, "dropping undecodable shield ${e.id} (${e.name})", it) }
                .getOrNull()
        }

    companion object {
        private const val TAG = "SpoilerAlert"
    }
}
