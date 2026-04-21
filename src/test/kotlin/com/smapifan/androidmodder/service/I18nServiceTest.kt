package com.smapifan.androidmodder.service

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class I18nServiceTest {

    @Test
    fun `returns english strings when locale is ENGLISH`() {
        val i18n = I18nService(Locale.ENGLISH)
        assertEquals("Android-Modder", i18n.get("app.title"))
        assertTrue(i18n.get("app.workspace").isNotBlank())
    }

    @Test
    fun `returns german strings when locale is GERMAN`() {
        val i18n = I18nService(Locale.GERMAN)
        assertEquals("Android-Modder", i18n.get("app.title"))
        assertEquals("Arbeitsverzeichnis", i18n.get("app.workspace"))
    }

    @Test
    fun `falls back to english for unsupported locale`() {
        val i18n = I18nService(Locale.JAPANESE)
        // Should not throw and should return something meaningful
        val title = i18n.get("app.title")
        assertEquals("Android-Modder", title)
    }

    @Test
    fun `returns placeholder for unknown key`() {
        val i18n = I18nService(Locale.ENGLISH)
        val result = i18n.get("no.such.key")
        assertTrue(result.startsWith("[") && result.endsWith("]"))
    }

    @Test
    fun `format substitutes arguments correctly`() {
        val i18n = I18nService(Locale.ENGLISH)
        val result = i18n.format("app.catalog.title", 14, 9, 10)
        assertTrue(result.contains("14"))
        assertTrue(result.contains("9"))
        assertTrue(result.contains("10"))
    }

    @Test
    fun `german format substitutes arguments correctly`() {
        val i18n = I18nService(Locale.GERMAN)
        val result = i18n.format("app.catalog.title", 14, 9, 10)
        assertTrue(result.contains("14"))
        assertTrue(result.contains("9"))
        assertTrue(result.contains("10"))
        assertFalse(result.startsWith("["))
    }

    @Test
    fun `all required keys are present in english bundle`() {
        val i18n = I18nService(Locale.ENGLISH)
        val requiredKeys = listOf(
            "app.title", "app.workspace", "app.cheats.loaded",
            "app.extensions.detected", "app.mods.detected",
            "app.catalog.title", "app.catalog.install.note", "mod.shell.note"
        )
        requiredKeys.forEach { key ->
            val value = i18n.get(key)
            assertFalse(value.startsWith("["), "Missing key in English bundle: $key")
        }
    }

    @Test
    fun `all required keys are present in german bundle`() {
        val i18n = I18nService(Locale.GERMAN)
        val requiredKeys = listOf(
            "app.title", "app.workspace", "app.cheats.loaded",
            "app.extensions.detected", "app.mods.detected",
            "app.catalog.title", "app.catalog.install.note", "mod.shell.note"
        )
        requiredKeys.forEach { key ->
            val value = i18n.get(key)
            assertFalse(value.startsWith("["), "Missing key in German bundle: $key")
        }
    }
}
