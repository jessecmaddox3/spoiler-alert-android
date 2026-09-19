package com.jessemaddox.spoileralert.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeaderArtTest {
    @Test
    fun `featured artwork and all font notices are present in app resources`() {
        HomeHeaderArtCatalog.featured.forEach {
            assertTrue(it.assetName, java.io.File("src/main/assets/home_headers/${it.assetName}").isFile)
        }
        for (name in listOf("dm_sans_regular", "dm_sans_medium", "dm_sans_bold", "roboto_condensed_bold", "roboto_condensed_black")) {
            assertTrue(name, java.io.File("src/main/res/font/$name.ttf").isFile)
        }
        for (name in listOf("DM-Sans-OFL.txt", "Roboto-Condensed-Apache-2.0.txt")) {
            assertTrue(name, java.io.File("src/main/assets/licenses/$name").readText().length > 4_000)
        }
    }

    @Test
    fun `daily page is stable and advances through the curated set`() {
        val count = HomeHeaderArtCatalog.featured.size

        assertEquals(HomeHeaderArtCatalog.pageForDay(20_000), HomeHeaderArtCatalog.pageForDay(20_000))
        assertEquals(
            (HomeHeaderArtCatalog.pageForDay(20_000) + 1) % count,
            HomeHeaderArtCatalog.pageForDay(20_001),
        )
    }

    @Test
    fun `daily page handles dates before the Unix epoch`() {
        val page = HomeHeaderArtCatalog.pageForDay(-1)

        assertTrue(page in HomeHeaderArtCatalog.featured.indices)
    }

    @Test
    fun `curated carousel uses distinct app assets`() {
        val art = HomeHeaderArtCatalog.featured

        assertTrue(art.size in 4..8)
        assertEquals(art.size, art.map { it.assetName }.distinct().size)
        assertTrue(art.all { it.assetName.endsWith(".webp") && it.description.isNotBlank() })
    }
}
