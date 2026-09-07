package com.jessemaddox.spoileralert.ui

internal data class HomeHeaderArtwork(
    val assetName: String,
    val description: String,
)

internal object HomeHeaderArtCatalog {
    /** Generic header states. Generated artwork is intentionally not redistributed. */
    val featured = listOf(
        HomeHeaderArtwork(
            "shield",
            "A shield protecting a quiet viewing experience",
        ),
        HomeHeaderArtwork(
            "quiet",
            "A quiet space for watching on your own time",
        ),
        HomeHeaderArtwork(
            "watch",
            "A calm viewing session",
        ),
        HomeHeaderArtwork(
            "hide",
            "Notifications held out of sight",
        ),
        HomeHeaderArtwork(
            "ready",
            "A ready-to-watch moment",
        ),
        HomeHeaderArtwork(
            "saved",
            "Spoilers safely saved for later",
        ),
    )

    fun pageForDay(epochDay: Long, pageCount: Int = featured.size): Int {
        require(pageCount > 0)
        return Math.floorMod(epochDay, pageCount.toLong()).toInt()
    }
}
