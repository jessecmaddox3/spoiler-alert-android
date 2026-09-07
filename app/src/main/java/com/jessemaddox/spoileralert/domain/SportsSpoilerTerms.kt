package com.jessemaddox.spoileralert.domain

/**
 * Strong game-state phrases that can reveal a result even when a notification omits both team
 * names. They are added at runtime only to TEAM/GAME shields, and therefore only participate
 * while that sports shield is armed. Kept deliberately narrower than generic words like
 * "won", "lead", "goal", or "half" to limit unrelated chat false positives.
 *
 * Phrases flagged `short` (e.g. "game over", "big win") are everyday enough to appear in innocent
 * chat, so STRICT messaging mode only counts them when paired with adjacent sports context.
 */
object SportsSpoilerTerms {
    val aliases: List<Alias> = listOf(
        Alias("scoreless"),
        Alias("goalless"),
        Alias("nil-nil"),
        Alias("nil nil"),
        Alias("no goals yet"),
        Alias("clean sheet"),
        Alias("shutout"),
        Alias("blowout"),
        Alias("equalizer"),
        Alias("equaliser"),
        Alias("hat trick"),
        Alias("penalty shootout"),
        Alias("final whistle"),
        Alias("final score"),
        Alias("halftime score"),
        Alias("game winner"),
        Alias("game-winning"),
        Alias("match winner"),
        Alias("walk-off"),
        Alias("walk off"),
        Alias("took the lead"),
        Alias("takes the lead"),
        Alias("blew it"),
        Alias("blew the lead"),
        Alias("blown lead"),
        Alias("what a game"),
        Alias("what a finish"),
        Alias("what a comeback"),
        Alias("what a heartbreaker"),
        Alias("instant classic"),
        Alias("comeback win"),
        Alias("upset win"),
        Alias("buzzer beater"),
        Alias("buzzer-beater"),
        Alias("choke job"),
        Alias("choked away"),
        Alias("clean sweep"),
        Alias("heartbreaking loss"),
        Alias("epic collapse"),
        Alias("sealed the win"),
        Alias("seals the win"),
        Alias("iced the game"),
        Alias("game over", short = true),
        Alias("swept", short = true),
        Alias("tough loss", short = true),
        Alias("big win", short = true),
        Alias("huge win", short = true),
        Alias("great game", short = true),
        Alias("got robbed", short = true),
    )
}
