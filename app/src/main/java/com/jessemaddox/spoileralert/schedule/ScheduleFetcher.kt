package com.jessemaddox.spoileralert.schedule

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.Reader

// PGA scoreboards include every player's live leaderboard data. The Open's live response was
// about 2.5M characters, so keep a firm bound with headroom for unusually large tournament fields.
internal const val MAX_SCHEDULE_RESPONSE_CHARS = 5_000_000

internal data class PublicEventState(val snapshot: ScoreSnapshot, val timeline: ScoreTimeline?)

/** Reads a response without ever allowing an unbounded network body into memory. */
internal object BoundedResponseReader {
    fun read(reader: Reader, maxChars: Int = MAX_SCHEDULE_RESPONSE_CHARS): String? {
        val buf = CharArray(8192)
        val body = StringBuilder()
        while (true) {
            val count = reader.read(buf)
            if (count == -1) return body.toString()
            if (body.length + count > maxChars) return null
            body.append(buf, 0, count)
        }
    }
}

/**
 * GET-only fetcher for ESPN's public team schedule and league scoreboard endpoints. This is
 * the app's ONLY network code path and it must stay that way: public schedules and status come
 * down, nothing ever goes up. It has no access to vault or notification content. Inputs are
 * catalog ESPN paths and public event ids; outputs are public game metadata.
 */
open class ScheduleFetcher {
    /** Public schedule refresh diagnostics only; no response content is retained here. */
    internal var hadFailure: Boolean = false
        private set

    /**
     * Fetch the schedule for an espn path like "football/nfl/1". Errors of any kind
     * (network, HTTP, malformed body) yield an empty list — schedule data is best-effort.
     */
    suspend fun fetch(espnPath: String): List<ScheduledGame> = withContext(Dispatchers.IO) {
        val parts = espnPath.split("/")
        if (parts.size != 3) {
            Log.w(TAG, "Malformed espn path: $espnPath")
            return@withContext emptyList()
        }
        val (sport, league, teamId) = parts
        val base = "https://site.api.espn.com/apis/site/v2/sports/$sport/$league/teams/$teamId/schedule"
        val games = get(base)?.let { EspnSchedule.parseTeamSchedule(it, teamId) }.orEmpty()
        // Soccer schedules split results (default) from upcoming fixtures (?fixture=true).
        val fixtures = if (sport == "soccer") {
            get("$base?fixture=true")?.let { EspnSchedule.parseTeamSchedule(it, teamId) }.orEmpty()
        } else emptyList()
        (games + fixtures).distinctBy { it.eventId }
    }

    /**
     * Fetch a league-wide scoreboard window (the Games-browser discovery pool). [scoreboardPath]
     * is "sport/league" (e.g. "football/nfl"); [datesParam] is "YYYYMMDD-YYYYMMDD"
     * ([EspnScoreboard.datesParam]); [extraQuery] appends endpoint quirks (cfb needs
     * "groups=80" to filter to FBS — verified working on scoreboard 2026-07-17). Errors of
     * any kind yield an empty list — schedule data is best-effort.
     */
    open suspend fun fetchScoreboard(
        scoreboardPath: String,
        leagueId: String,
        datesParam: String,
        extraQuery: String? = null,
        allowTeamless: Boolean = false,
        minimumDurationMillis: Long = 0L,
    ): List<LeagueGame> = withContext(Dispatchers.IO) {
        val query = "dates=$datesParam&limit=300" + (extraQuery?.let { "&$it" } ?: "")
        val url = "https://site.api.espn.com/apis/site/v2/sports/$scoreboardPath/scoreboard?$query"
        get(url)?.let {
            if (!EspnScoreboard.hasScheduleEnvelope(it)) {
                hadFailure = true
                return@withContext emptyList()
            }
            EspnScoreboard.parse(
                it, leagueId, allowTeamless = allowTeamless,
                minimumDurationMillis = minimumDurationMillis,
            )
        }.orEmpty()
    }

    /** One-event public summary, preferred for a fresh live answer over a league cache. */
    open suspend fun fetchScoreSummary(
        scoreboardPath: String,
        leagueId: String,
        eventId: String,
    ): ScoreSnapshot? = withContext(Dispatchers.IO) {
        if (!eventId.matches(Regex("[A-Za-z0-9_-]+"))) return@withContext null
        val url = "https://site.api.espn.com/apis/site/v2/sports/$scoreboardPath/summary?event=$eventId"
        get(url)?.let { EspnSummary.parseScoreSnapshot(it, leagueId) }
    }

    /** One response supplies consistent progress, count, and optional viewing cues. */
    internal open suspend fun fetchEventState(
        scoreboardPath: String, leagueId: String, eventId: String,
    ): PublicEventState? = withContext(Dispatchers.IO) {
        if (!eventId.matches(Regex("[A-Za-z0-9_-]+"))) return@withContext null
        val body = get("https://site.api.espn.com/apis/site/v2/sports/$scoreboardPath/summary?event=$eventId")
            ?: return@withContext null
        val snapshot = EspnSummary.parseScoreSnapshot(body, leagueId) ?: return@withContext null
        PublicEventState(snapshot, EspnTimeline.parse(body, leagueId))
    }

    /** Normalized play history stays private; only requested time cues leave the reducer. */
    internal open suspend fun fetchScoreTimeline(
        scoreboardPath: String,
        leagueId: String,
        eventId: String,
    ): ScoreTimeline? = withContext(Dispatchers.IO) {
        if (!eventId.matches(Regex("[A-Za-z0-9_-]+"))) return@withContext null
        val url = "https://site.api.espn.com/apis/site/v2/sports/$scoreboardPath/summary?event=$eventId"
        get(url)?.let { EspnTimeline.parse(it, leagueId) }
    }

    /** Full golf leaderboard fallback. The parser immediately reduces it to coarse fields. */
    open suspend fun fetchGolfScoreboardSnapshot(
        scoreboardPath: String,
        eventId: String,
        dates: String,
        extraQuery: String? = null,
    ): ScoreSnapshot? = withContext(Dispatchers.IO) {
        if (!eventId.matches(Regex("[A-Za-z0-9_-]+"))) return@withContext null
        val query = listOfNotNull("dates=$dates", extraQuery).joinToString("&")
        val url = "https://site.api.espn.com/apis/site/v2/sports/$scoreboardPath/scoreboard?$query"
        get(url)?.let { EspnGolfScoreboard.parseScoreSnapshot(it, eventId) }
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        val result = try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $url")
                null
            } else {
                conn.inputStream.bufferedReader().use { r ->
                    BoundedResponseReader.read(r).also { body ->
                        if (body == null) {
                            Log.w(TAG, "response exceeds bounded read limit: $url")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Schedule fetch failed for $url: $e")
            null
        } finally {
            conn?.disconnect()
        }
        if (result == null) hadFailure = true
        return result
    }

    private companion object {
        const val TAG = "ScheduleFetcher"
        const val TIMEOUT_MS = 10_000
        const val USER_AGENT = "SpoilerAlert/3.10.0 (Android; public sports GET only)"
    }
}
