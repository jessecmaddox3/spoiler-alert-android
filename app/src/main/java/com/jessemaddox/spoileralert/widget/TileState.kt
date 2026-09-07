package com.jessemaddox.spoileralert.widget

import android.content.Context
import com.jessemaddox.spoileralert.data.GameWindows
import com.jessemaddox.spoileralert.data.ShieldCodec
import com.jessemaddox.spoileralert.data.ShieldRepository
import com.jessemaddox.spoileralert.domain.TeamCatalog
import kotlinx.coroutines.flow.first

/**
 * One-shot snapshot the Quick Settings tile renders from (v3 amendment item 9). Unlike
 * [WidgetState] this is read fresh on every onStartListening/onClick rather than observed
 * continuously — a TileService has no recomposing UI to keep live — but it follows the same
 * public-metadata-only rule: armed state + next-game scheduling, NEVER vault content.
 */
data class TileState(
    val armed: Boolean = false,
    /** ACTIVE tile: soonest-expiring armed shield's name + expiry, for the subtitle. */
    val activeName: String? = null,
    val activeExpiresAtMillis: Long? = null,
    /** INACTIVE tile: unarmed TEAM shields with a next schedulable game — see [TileDecision]. */
    val idleCandidates: List<TileDecision.Candidate> = emptyList(),
) {
    companion object {
        /** [repo] defaults to the real app singleton; instrumented tests can pass one built
         *  over an isolated in-memory [com.jessemaddox.spoileralert.data.AppDatabase] instead
         *  (same pattern as [ShieldRepository]'s own test constructor). */
        suspend fun snapshot(context: Context, repo: ShieldRepository = ShieldRepository(context)): TileState {
            val app = context.applicationContext
            val shields = repo.shieldDao.observeAll().first()
            val armedShields = shields.filter { it.armed }
            if (armedShields.isNotEmpty()) {
                // Show the deadline the user chose. The internal unanswered-check-in grace stays
                // invisible and continues to govern interception after this time.
                val soonest = armedShields.minByOrNull {
                    ShieldCodec.sessionDeadlineMillis(it) ?: Long.MAX_VALUE
                }
                return TileState(
                    armed = true,
                    activeName = soonest?.name,
                    activeExpiresAtMillis = soonest?.let { ShieldCodec.sessionDeadlineMillis(it) },
                )
            }

            // Idle: TEAM shields' next schedulable game — see [TileDecision] for why the
            // ambiguity check is count-based rather than a time-window heuristic.
            val catalog = runCatching {
                TeamCatalog.parse(app.assets.open("teams.json").bufferedReader().readText())
            }.getOrNull()
            val since = GameWindows.sinceMillis(System.currentTimeMillis())
            val candidates = shields.filter { it.kind == "TEAM" }.mapNotNull { s ->
                val game = repo.gameDao.upcomingForShield(s.id, since).firstOrNull() ?: return@mapNotNull null
                TileDecision.Candidate(
                    shieldId = s.id,
                    name = game.shortName,
                    leagueId = catalog?.leagueIdForTeam(s.catalogTeamId),
                )
            }
            return TileState(armed = false, idleCandidates = candidates)
        }
    }
}
