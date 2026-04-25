package com.smapifan.androidmodder.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CodePatchConfig] after the removal of the init-block validation.
 *
 * Previously, CodePatchConfig.init{} enforced hard constraints (non-blank name/gameId,
 * non-empty patches, no blank targetFiles, no wildcard extensions). That validation
 * was removed so that the model is a plain data class. These tests confirm that
 * previously-illegal instances are now constructable without throwing.
 */
class CodePatchConfigTest {

    // ── Construction without validation ────────────────────────────────────────

    @Test
    fun `blank name is accepted without throwing`() {
        // Before: would throw IllegalArgumentException("CodePatchConfig.name must not be blank")
        val config = CodePatchConfig(name = "", gameId = "com.example.game")
        assertEquals("", config.name)
    }

    @Test
    fun `whitespace-only name is accepted without throwing`() {
        val config = CodePatchConfig(name = "   ", gameId = "com.example.game")
        assertEquals("   ", config.name)
    }

    @Test
    fun `blank gameId is accepted without throwing`() {
        // Before: would throw IllegalArgumentException("CodePatchConfig.gameId must not be blank")
        val config = CodePatchConfig(name = "MyPatch", gameId = "")
        assertEquals("", config.gameId)
    }

    @Test
    fun `empty patches list is accepted without throwing`() {
        // Before: would throw IllegalArgumentException("CodePatchConfig.patches must not be empty")
        val config = CodePatchConfig(name = "MyPatch", gameId = "com.example.game", patches = emptyList())
        assertTrue(config.patches.isEmpty())
    }

    @Test
    fun `blank entry in targetFiles is accepted without throwing`() {
        // Before: would throw IllegalArgumentException("CodePatchConfig.targetFiles must not contain blank entries")
        val config = CodePatchConfig(
            name = "MyPatch",
            gameId = "com.example.game",
            patches = listOf(CodePatchDefinition("COINS", "999")),
            targetFiles = listOf("")
        )
        assertEquals(listOf(""), config.targetFiles)
    }

    @Test
    fun `wildcard star extension is accepted without throwing`() {
        // Before: would throw because "*" is wildcard-only
        val config = CodePatchConfig(
            name = "MyPatch",
            gameId = "com.example.game",
            patches = listOf(CodePatchDefinition("COINS", "999")),
            extensions = listOf("*")
        )
        assertEquals(listOf("*"), config.extensions)
    }

    @Test
    fun `wildcard dot-star extension is accepted without throwing`() {
        // Before: would throw because ".*" is wildcard-only
        val config = CodePatchConfig(
            name = "MyPatch",
            gameId = "com.example.game",
            patches = listOf(CodePatchDefinition("COINS", "999")),
            extensions = listOf(".*")
        )
        assertEquals(listOf(".*"), config.extensions)
    }

    @Test
    fun `blank extension entry is accepted without throwing`() {
        // Before: would throw because "" is a blank extension
        val config = CodePatchConfig(
            name = "MyPatch",
            gameId = "com.example.game",
            patches = listOf(CodePatchDefinition("COINS", "999")),
            extensions = listOf("")
        )
        assertEquals(listOf(""), config.extensions)
    }

    @Test
    fun `multiple formerly-invalid extensions accepted together`() {
        val config = CodePatchConfig(
            name = "MyPatch",
            gameId = "com.example.game",
            extensions = listOf("*", ".*", "", "  ")
        )
        assertEquals(listOf("*", ".*", "", "  "), config.extensions)
    }

    // ── Default values still correct ───────────────────────────────────────────

    @Test
    fun `default targetFiles is empty list`() {
        val config = CodePatchConfig(name = "p", gameId = "com.example.game")
        assertTrue(config.targetFiles.isEmpty())
    }

    @Test
    fun `default extensions is empty list`() {
        val config = CodePatchConfig(name = "p", gameId = "com.example.game")
        assertTrue(config.extensions.isEmpty())
    }

    @Test
    fun `default patches is empty list`() {
        val config = CodePatchConfig(name = "p", gameId = "com.example.game")
        assertTrue(config.patches.isEmpty())
    }

    @Test
    fun `default description is empty string`() {
        val config = CodePatchConfig(name = "p", gameId = "com.example.game")
        assertEquals("", config.description)
    }

    // ── Valid round-trip ────────────────────────────────────────────────────────

    @Test
    fun `all fields stored and retrieved correctly`() {
        val patch = CodePatchDefinition("STARTING_COINS", "999999")
        val config = CodePatchConfig(
            name        = "CoinsBoost",
            gameId      = "com.kiloo.subwaysurf",
            description = "Boost starting coins",
            targetFiles = listOf("com.kiloo.subwaysurf/GameConfig.cs"),
            extensions  = listOf(".cs"),
            patches     = listOf(patch)
        )

        assertEquals("CoinsBoost", config.name)
        assertEquals("com.kiloo.subwaysurf", config.gameId)
        assertEquals("Boost starting coins", config.description)
        assertEquals(listOf("com.kiloo.subwaysurf/GameConfig.cs"), config.targetFiles)
        assertEquals(listOf(".cs"), config.extensions)
        assertEquals(1, config.patches.size)
        assertEquals("STARTING_COINS", config.patches[0].identifier)
        assertEquals("999999", config.patches[0].newValue)
        assertNull(config.patches[0].expectedOldValue)
    }

    // ── Boundary: completely empty / minimal configs ────────────────────────────

    @Test
    fun `all-defaults instance has minimal required fields only`() {
        // Every optional field should be at its default
        val config = CodePatchConfig(name = "x", gameId = "y")
        assertEquals("x", config.name)
        assertEquals("y", config.gameId)
        assertEquals("", config.description)
        assertTrue(config.targetFiles.isEmpty())
        assertTrue(config.extensions.isEmpty())
        assertTrue(config.patches.isEmpty())
    }

    @Test
    fun `fully blank config is constructable`() {
        // Both required fields blank – was a double IllegalArgumentException before
        val config = CodePatchConfig(name = "", gameId = "")
        assertEquals("", config.name)
        assertEquals("", config.gameId)
    }
}