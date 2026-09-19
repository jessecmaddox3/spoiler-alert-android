package com.jessemaddox.spoileralert.ui

internal data class HomeHeaderArtwork(
    val assetName: String,
    val description: String,
)

internal object HomeHeaderArtCatalog {
    /** A deliberately varied subset of the full candidate library. */
    val featured = listOf(
        HomeHeaderArtwork(
            "01_cinematic_stadium_bubble.webp",
            "A sports fan relaxing inside a glowing bubble amid many live sports",
        ),
        HomeHeaderArtwork(
            "02_rube_goldberg_vault.webp",
            "A playful machine catching sports notifications before they reach a fan",
        ),
        HomeHeaderArtwork(
            "04_sports_multiverse.webp",
            "A calm fan surrounded by colorful portals into different sports",
        ),
        HomeHeaderArtwork(
            "06_paper_vault_panorama.webp",
            "A paper-craft sports world tucking notifications safely out of sight",
        ),
        HomeHeaderArtwork(
            "09_shielded_fan_mural.webp",
            "A lively sports mural surrounding a calm fan in a quiet, spoiler-free room",
        ),
        HomeHeaderArtwork(
            "12_storybook_sports_lagoon.webp",
            "A storybook sports landscape diverting spoilers away from a calm lagoon",
        ),
    )

    fun pageForDay(epochDay: Long, pageCount: Int = featured.size): Int {
        require(pageCount > 0)
        return Math.floorMod(epochDay, pageCount.toLong()).toInt()
    }
}
