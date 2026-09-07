package com.jessemaddox.spoileralert.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeaderArtTest {
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
    fun `generic carousel states are distinct and described`() {
        val art = HomeHeaderArtCatalog.featured

        assertTrue(art.size >= 4)
        assertEquals(art.size, art.map { it.assetName }.distinct().size)
        assertTrue(art.all { it.description.isNotBlank() })
    }
}
