package com.jessemaddox.spoileralert.ui

import android.content.Context
import java.util.UUID

object AppPrefs {
    private const val FILE = "prefs"
    private const val KEY_EXCLUDED = "excludedPackages"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_AUTO_DISARM_HOURS = "autoDisarmHours"
    private const val KEY_MISSES = "cancelMisses"
    private const val KEY_LISTENER_CONNECTED = "listenerConnected"
    private const val KEY_NOTIFICATION_ACTION_TOKEN = "notificationActionToken"

    /**
     * Private capability carried by notification deep links into the exported launcher activity.
     * Without it, another app could forge an intent containing a vault row id and reveal content.
     */
    @Synchronized
    fun notificationActionToken(context: Context): String {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString(KEY_NOTIFICATION_ACTION_TOKEN, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            // Commit before handing out the capability so a process restart cannot invalidate an
            // already-posted notification action.
            prefs.edit().putString(KEY_NOTIFICATION_ACTION_TOKEN, it).commit()
        }
    }

    fun isValidNotificationActionToken(context: Context, candidate: String?): Boolean =
        candidate != null && candidate == notificationActionToken(context)

    fun excludedPackages(context: Context): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED, emptySet()) ?: emptySet()

    fun setExcluded(context: Context, packages: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_EXCLUDED, packages).apply()
    }

    private const val KEY_FANTASY = "fantasyPackages"

    /** Fantasy-app shields (v3 amendment item 3): package names the user has designated as
     *  fantasy sports apps. While any session is active, EVERY notification from these
     *  packages is hidden — see [com.jessemaddox.spoileralert.domain.FantasyPolicy]. */
    fun fantasyPackages(context: Context): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_FANTASY, emptySet()) ?: emptySet()

    fun setFantasyPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_FANTASY, packages).apply()
    }

    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** Default protection-session length applied to newly created shields. */
    fun autoDisarmHours(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_AUTO_DISARM_HOURS, 4)

    fun setAutoDisarmHours(context: Context, hours: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AUTO_DISARM_HOURS, hours.coerceIn(1, 72)).apply()
    }

    /** Records "cancel didn't stick" events as "package|epochMillis" strings, newest last, max 20 kept. */
    fun recordCancelMiss(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val existing = prefs.getStringSet(KEY_MISSES, emptySet()).orEmpty()
        val entry = "$packageName|${System.currentTimeMillis()}"
        val trimmed = (existing + entry).sortedBy { it.substringAfter('|').toLongOrNull() ?: 0L }.takeLast(20).toSet()
        prefs.edit().putStringSet(KEY_MISSES, trimmed).apply()
    }

    fun cancelMisses(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_MISSES, emptySet()).orEmpty()
            .sortedByDescending { it.substringAfter('|').toLongOrNull() ?: 0L }

    private const val KEY_TRACE = "matchTrace"

    /** Appends a match-decision diagnostic line ("epochMillis|text"), keeping the newest 40. */
    fun recordTrace(context: Context, line: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TRACE, "").orEmpty()
        val entry = "${System.currentTimeMillis()}|${line.replace('\n', ' ')}"
        val lines = (existing.split('\n').filter { it.isNotBlank() } + entry).takeLast(40)
        prefs.edit().putString(KEY_TRACE, lines.joinToString("\n")).apply()
    }

    /** Newest first. */
    fun matchTrace(context: Context): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_TRACE, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }.reversed()

    fun clearTrace(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_TRACE).apply()
    }

    private const val KEY_PREGAME_PROMPTS = "pregamePromptsEnabled"

    /** Global switch for the "arm your shield?" prompt ~10 minutes before kickoff. */
    fun pregamePromptsEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_PREGAME_PROMPTS, true)

    fun setPregamePromptsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREGAME_PROMPTS, enabled).apply()
    }

    private const val KEY_FIRST_CYCLE = "completedFirstCycle"

    /** True once the user has completed a full hide → explicit reveal-all cycle; the Home
     *   01/02/03 steps strip teaches until then (Settings > About keeps a "How it works"). */
    fun completedFirstCycle(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_FIRST_CYCLE, false)

    fun setCompletedFirstCycle(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FIRST_CYCLE, true).apply()
    }

    private const val KEY_LIFETIME_HIDDEN = "lifetimeHidden"

    /** On-device lifetime tally of genuinely NEW hidden items (v3 extras). Incremented once per
     *  new vault row — never on re-posts/updates — from [com.jessemaddox.spoileralert.data.ShieldRepository.upsertVaultHidden]'s
     *  insert branch. Displayed as a quiet Home footer stat; never gamified. */
    fun incrementLifetimeHidden(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LIFETIME_HIDDEN, prefs.getInt(KEY_LIFETIME_HIDDEN, 0) + 1).apply()
    }

    fun lifetimeHidden(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_LIFETIME_HIDDEN, 0)

    fun setListenerConnected(context: Context, connected: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LISTENER_CONNECTED, connected).apply()
    }

    /** Best-effort: true unless the service reported a disconnect it hasn't recovered from. */
    fun listenerConnected(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_LISTENER_CONNECTED, true)
}
