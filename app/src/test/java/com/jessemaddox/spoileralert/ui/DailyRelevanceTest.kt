package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.GameEntity
import com.jessemaddox.spoileralert.data.LeagueGameEntity
import com.jessemaddox.spoileralert.data.ShieldEntity
import com.jessemaddox.spoileralert.domain.League
import com.jessemaddox.spoileralert.domain.Team
import com.jessemaddox.spoileralert.domain.TeamCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyRelevanceTest {
    private val atlanta = ZoneId.of("America/New_York")
    private val now = at(2026, 7, 19, 10)

    @Test fun `expired hiding flag cannot suppress today's followed-team game`() {
        val stale = ShieldEntity(id = 7, name = "Atlanta United", aliasesJson = "[]", kind = "TEAM",
            armed = true, armedAtMillis = now - 21 * 86_400_000L)
        val today = teamGame("today", 7, at(2026, 7, 19, 13))
        val feed = DailyRelevance.build(listOf(stale), listOf(today), emptyList(), now, atlanta)
        assertEquals(listOf("today"), feed.personal.map { it.game.id })
    }

    @Test fun `all saved-team games today appear and tomorrow stays off Home`() {
        val shield = ShieldEntity(id = 7, name = "Atlanta United", aliasesJson = "[]", kind = "TEAM")
        val todayOne = teamGame("one", 7, at(2026, 7, 19, 13))
        val todayTwo = teamGame("two", 7, at(2026, 7, 19, 20))
        val tomorrow = teamGame("tomorrow", 7, at(2026, 7, 20, 12))

        val feed = DailyRelevance.build(listOf(shield), listOf(todayTwo, tomorrow, todayOne), emptyList(), now, atlanta)

        assertEquals(listOf("one", "two"), feed.personal.map { it.game.id })
    }

    @Test fun `The Open remains featured across its multi-day window`() {
        val open = leagueGame(
            id = "open", league = "golf", name = "The Open",
            start = at(2026, 7, 16, 0), end = at(2026, 7, 20, 0),
        )
        val feed = DailyRelevance.build(emptyList(), emptyList(), listOf(open), now, atlanta)
        assertEquals("Golf major", feed.featured.single().reason)
    }

    @Test fun `saved Open interest moves live major into For you today`() {
        val shield = ShieldEntity(
            id = 11, name = "The Open Championship", aliasesJson = "[]", kind = "TEAM",
            catalogTeamId = "golf-open-championship",
        )
        val catalog = TeamCatalog(listOf(League("golf", "Golf", listOf(
            Team("golf-open-championship", "The Open Championship", short = listOf("The Open")),
        ))))
        val open = leagueGame(
            id = "open", league = "golf", name = "The Open",
            start = at(2026, 7, 16, 0), end = at(2026, 7, 20, 0),
        )

        val feed = DailyRelevance.build(
            listOf(shield), emptyList(), listOf(open), now, atlanta, catalog,
        )

        assertEquals(listOf("open"), feed.interests.map { it.game.eventId })
        assertTrue(feed.featured.isEmpty())
    }

    @Test fun `armed multi-day game shield suppresses stale available Open card`() {
        val interest = ShieldEntity(
            id = 11, name = "The Open Championship", aliasesJson = "[]", kind = "TEAM",
            catalogTeamId = "golf-open-championship",
        )
        val armedGame = ShieldEntity(
            id = 12, name = "The Open", aliasesJson = "[]", kind = "GAME",
            gameEventId = "open", armed = true, armedAtMillis = now,
        )
        val catalog = TeamCatalog(listOf(League("golf", "Golf", listOf(
            Team("golf-open-championship", "The Open Championship", short = listOf("The Open")),
        ))))
        val open = leagueGame(
            id = "open", league = "golf", name = "The Open",
            start = at(2026, 7, 16, 0), end = at(2026, 7, 20, 0),
        )

        val feed = DailyRelevance.build(
            listOf(interest, armedGame), emptyList(), listOf(open), now, atlanta, catalog,
        )

        assertTrue(feed.interests.isEmpty())
        assertTrue(feed.featured.isEmpty())
    }

    @Test fun `saved Open interest does not claim an unrelated golf event`() {
        val shield = ShieldEntity(
            id = 11, name = "The Open Championship", aliasesJson = "[]", kind = "TEAM",
            catalogTeamId = "golf-open-championship",
        )
        val catalog = TeamCatalog(listOf(League("golf", "Golf", listOf(
            Team("golf-open-championship", "The Open Championship", short = listOf("The Open")),
        ))))
        val ordinary = leagueGame(
            id = "ordinary", league = "golf", name = "Travelers Championship",
            start = at(2026, 7, 19, 9), end = at(2026, 7, 19, 19),
        )

        val feed = DailyRelevance.build(
            listOf(shield), emptyList(), listOf(ordinary), now, atlanta, catalog,
        )

        assertTrue(feed.interests.isEmpty())
    }

    @Test fun `saved Formula 1 constructor maps to today's race weekend`() {
        val shield = ShieldEntity(
            id = 12, name = "McLaren", aliasesJson = "[]", kind = "TEAM",
            catalogTeamId = "f1-mclaren",
        )
        val catalog = TeamCatalog(listOf(League("f1", "Formula 1", listOf(
            Team("f1-mclaren", "McLaren"),
        ))))
        val race = leagueGame(
            id = "race", league = "f1", name = "Belgian Grand Prix",
            start = at(2026, 7, 19, 9), end = at(2026, 7, 19, 13),
        )

        val feed = DailyRelevance.build(
            listOf(shield), emptyList(), listOf(race), now, atlanta, catalog,
        )

        assertEquals(listOf("race"), feed.interests.map { it.game.eventId })
    }

    @Test fun `world cup third place and final are featured without saved teams`() {
        val third = leagueGame("third", "soccer", "England at France", at(2026, 7, 19, 12), phase = "3rd-place-match")
        val final = leagueGame("final", "soccer", "Argentina at Spain", at(2026, 7, 19, 15), phase = "final")
        val feed = DailyRelevance.build(emptyList(), emptyList(), listOf(third, final), now, atlanta)
        assertEquals(
            listOf("World Cup third-place match", "World Cup final"),
            feed.featured.map { it.reason },
        )
    }

    @Test fun `monday and thursday night football are event-like but ordinary games are not`() {
        val monday = leagueGame("mnf", "nfl", "DEN at KC", at(2026, 9, 14, 20))
        val thursday = leagueGame("tnf", "nfl", "DET at BUF", at(2026, 9, 17, 20))
        val sundayDay = leagueGame("sun", "nfl", "CAR at ATL", at(2026, 9, 20, 13))
        assertEquals("Monday Night Football", DailyRelevance.featuredReason(monday, atlanta))
        assertEquals("Thursday Night Football", DailyRelevance.featuredReason(thursday, atlanta))
        assertEquals(null, DailyRelevance.featuredReason(sundayDay, atlanta))
    }

    @Test fun `primetime classification uses Eastern kickoff rather than device timezone`() {
        val sundayNight = leagueGame("snf", "nfl", "DAL at NYG", at(2026, 9, 20, 20))
        val thanksgivingAfternoon = leagueGame(
            "thanksgiving", "nfl", "CHI at DET", at(2026, 11, 26, 13),
        )
        val pacific = ZoneId.of("America/Los_Angeles")

        assertEquals("Sunday Night Football", DailyRelevance.featuredReason(sundayNight, pacific))
        assertEquals(null, DailyRelevance.featuredReason(thanksgivingAfternoon, pacific))
    }

    @Test fun `saved Monday Night Football interest maps to the actual Monday game`() {
        val interest = ShieldEntity(
            id = 44, name = "Monday Night Football", aliasesJson = "[]", kind = "TEAM",
            catalogTeamId = "nfl-monday-night-football",
        )
        val catalog = TeamCatalog(listOf(League("nfl", "NFL", listOf(
            Team("nfl-monday-night-football", "Monday Night Football"),
        ))))
        val monday = leagueGame("mnf", "nfl", "DEN at KC", at(2026, 9, 14, 20))

        val feed = DailyRelevance.build(
            listOf(interest), emptyList(), listOf(monday), at(2026, 9, 14, 12), atlanta, catalog,
        )

        assertEquals(listOf("mnf"), feed.interests.map { it.game.eventId })
        assertTrue(feed.featured.isEmpty())
    }

    @Test fun `ordinary unrelated events stay in Find an event only`() {
        val ordinary = leagueGame("ordinary", "mlb", "Miami at Atlanta", at(2026, 7, 19, 13))
        val tgl = leagueGame("tgl", "tgl", "Atlanta Drive GC vs Jupiter Links", at(2026, 7, 19, 19))
        val feed = DailyRelevance.build(emptyList(), emptyList(), listOf(ordinary, tgl), now, atlanta)
        assertTrue(feed.featured.isEmpty())
        assertEquals(2, feed.otherToday.count)
        assertEquals(listOf("mlb", "tgl"), feed.otherToday.leagueIds)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, atlanta).toInstant().toEpochMilli()

    private fun teamGame(id: String, shieldId: Long, start: Long) = GameEntity(
        id, shieldId, id, id, start, completed = false, fetchedAtMillis = now,
    )

    private fun leagueGame(
        id: String,
        league: String,
        name: String,
        start: Long,
        end: Long? = null,
        phase: String? = null,
    ) = LeagueGameEntity(
        eventId = id, leagueId = league, name = name, shortName = name,
        startMillis = start, completed = false, homeEspnId = "1", awayEspnId = "2",
        label = null, fetchedAtMillis = now, endMillis = end, phase = phase,
    )
}
