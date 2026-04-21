package com.smapifan.androidmodder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppCatalogServiceTest {

    private val service = AppCatalogService()

    private val sampleJson = """
        [
          { "name": "Merge Dragons!", "packageName": "com.gram.mergedragons",                          "category": "Spiele",   "minAgeRating": 0  },
          { "name": "YouTube Kids",   "packageName": "com.google.android.apps.youtube.kids",           "category": "GApps",    "minAgeRating": 0  },
          { "name": "Gmail",          "packageName": "com.google.android.gm",                          "category": "GApps",    "minAgeRating": 6  },
          { "name": "YouTube",        "packageName": "com.google.android.youtube",                     "category": "GApps",    "minAgeRating": 12 },
          { "name": "Clash of Clans", "packageName": "com.supercell.clashofclans",                     "category": "Spiele",   "minAgeRating": 12 }
        ]
    """.trimIndent()

    @Test
    fun `parses catalog json correctly`() {
        val entries = service.parse(sampleJson)
        assertEquals(5, entries.size)
        assertEquals("Merge Dragons!", entries[0].name)
        assertEquals("com.gram.mergedragons", entries[0].packageName)
        assertEquals("Spiele", entries[0].category)
        assertEquals(0, entries[0].minAgeRating)
    }

    @Test
    fun `filterByAge returns all entries for age 18`() {
        val entries = service.parse(sampleJson)
        val filtered = service.filterByAge(entries, 18)
        assertEquals(5, filtered.size)
    }

    @Test
    fun `filterByAge returns only age-0 entries for a young child`() {
        val entries = service.parse(sampleJson)
        val filtered = service.filterByAge(entries, 5)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.minAgeRating <= 5 })
        assertTrue(filtered.any { it.packageName == "com.gram.mergedragons" })
        assertTrue(filtered.any { it.packageName == "com.google.android.apps.youtube.kids" })
    }

    @Test
    fun `filterByAge respects boundary at exact age`() {
        val entries = service.parse(sampleJson)
        val filtered = service.filterByAge(entries, 12)
        assertEquals(5, filtered.size)
    }

    @Test
    fun `filterByAge excludes apps above user age`() {
        val entries = service.parse(sampleJson)
        val filtered = service.filterByAge(entries, 11)
        assertTrue(filtered.none { it.minAgeRating > 11 })
    }

    @Test
    fun `playStoreUrl generates correct url`() {
        val entries = service.parse(sampleJson)
        val url = service.playStoreUrl(entries.first())
        assertEquals("https://play.google.com/store/apps/details?id=com.gram.mergedragons", url)
    }

    @Test
    fun `filterByAge with maxValue returns full catalog`() {
        val entries = service.parse(sampleJson)
        val filtered = service.filterByAge(entries, Int.MAX_VALUE)
        assertEquals(entries.size, filtered.size)
    }
}
