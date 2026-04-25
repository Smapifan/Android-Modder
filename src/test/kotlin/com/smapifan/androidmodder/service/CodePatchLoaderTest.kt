package com.smapifan.androidmodder.service

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodePatchLoaderTest {

    @Test
    fun `invalid codepatch file is reported but does not crash`() {
        val ws = Files.createTempDirectory("codepatch-loader-invalid")
        val gameDir = ws.resolve("com.gram.mergedragons").also { it.createDirectories() }

        ws.resolve("broken.codepatch").writeText("{ invalid json")

        val loader = CodePatchLoader()
        val report = loader.applyForGame(ws, "com.gram.mergedragons", gameDir)

        assertEquals(1, report.configFilesDiscovered)
        assertEquals(0, report.configFilesLoaded)
        assertTrue(report.errors.isNotEmpty())
    }

    @Test
    fun `discovers and applies codepatch file with explicit target file`() {
        val ws = Files.createTempDirectory("codepatch-loader")
        val gameDir = ws.resolve("com.gram.mergedragons").also { it.createDirectories() }
        val source = gameDir.resolve("DragonRanch.Shared.cs")
        source.writeText("private const float K_CHANCE_OF_DRAGON_STAR = 0.05;")

        ws.resolve("IncreaseDragonStarChance.codepatch").writeText(
            """
            {
              "name": "IncreaseDragonStarChance",
              "gameId": "com.gram.mergedragons",
              "targetFiles": ["com.gram.mergedragons/DragonRanch.Shared.cs"],
              "patches": [
                {
                  "identifier": "K_CHANCE_OF_DRAGON_STAR",
                  "newValue": "0.5",
                  "expectedOldValue": "0.05"
                }
              ]
            }
            """.trimIndent()
        )

        val loader = CodePatchLoader()
        val report = loader.applyForGame(ws, "com.gram.mergedragons", gameDir)

        assertEquals(1, report.configFilesDiscovered)
        assertEquals(1, report.configFilesLoaded)
        assertEquals(1, report.filesVisited)
        assertEquals(1, report.filesPatched)
        assertTrue(source.readText().contains("= 0.5"))
    }

    // ── patches without expectedOldValue (CoinsBoost.codepatch pattern) ────────

    @Test
    fun `patch without expectedOldValue is applied unconditionally`() {
        // Mirrors the CoinsBoost.codepatch change: "expectedOldValue" field removed,
        // so the patch must fire regardless of the current literal value.
        val ws = Files.createTempDirectory("codepatch-no-expected-old")
        val gameDir = ws.resolve("com.kiloo.subwaysurf").also { it.createDirectories() }
        val source = gameDir.resolve("GameConfig.cs")
        source.writeText("private const int STARTING_COINS = 500;")

        ws.resolve("CoinsBoost.codepatch").writeText(
            """
            {
              "name": "CoinsBoost",
              "gameId": "com.kiloo.subwaysurf",
              "targetFiles": ["com.kiloo.subwaysurf/GameConfig.cs"],
              "patches": [
                {
                  "identifier": "STARTING_COINS",
                  "newValue": "999999"
                }
              ]
            }
            """.trimIndent()
        )

        val loader = CodePatchLoader()
        val report = loader.applyForGame(ws, "com.kiloo.subwaysurf", gameDir)

        assertEquals(1, report.configFilesLoaded)
        assertEquals(1, report.filesPatched)
        assertTrue(source.readText().contains("= 999999"), "Patch must be applied even without expectedOldValue")
    }

    @Test
    fun `patch without expectedOldValue fires even when current value differs from any expected`() {
        // If the game updated its default (e.g. changed 500 → 250), a patch without
        // expectedOldValue should still apply. This is the key behavioural change vs
        // having expectedOldValue="500" which would silently skip after a game update.
        val ws = Files.createTempDirectory("codepatch-no-expected-old-changed")
        val gameDir = ws.resolve("com.kiloo.subwaysurf").also { it.createDirectories() }
        val source = gameDir.resolve("GameConfig.cs")
        // Simulate the game having been updated: default is now 250, not 500
        source.writeText("private const int STARTING_COINS = 250;")

        ws.resolve("CoinsBoost.codepatch").writeText(
            """
            {
              "name": "CoinsBoost",
              "gameId": "com.kiloo.subwaysurf",
              "targetFiles": ["com.kiloo.subwaysurf/GameConfig.cs"],
              "patches": [
                {
                  "identifier": "STARTING_COINS",
                  "newValue": "999999"
                }
              ]
            }
            """.trimIndent()
        )

        val loader = CodePatchLoader()
        val report = loader.applyForGame(ws, "com.kiloo.subwaysurf", gameDir)

        assertEquals(1, report.filesPatched)
        assertTrue(
            source.readText().contains("= 999999"),
            "Unconditional patch must apply even when current value differs from original default"
        )
    }

    @Test
    fun `codepatch without expectedOldValue reports correct metrics`() {
        val ws = Files.createTempDirectory("codepatch-no-expected-metrics")
        val gameDir = ws.resolve("com.kiloo.subwaysurf").also { it.createDirectories() }
        val source = gameDir.resolve("Config.cs")
        source.writeText("private const int STARTING_COINS = 500;")

        ws.resolve("boost.codepatch").writeText(
            """
            {
              "name": "CoinsBoost",
              "gameId": "com.kiloo.subwaysurf",
              "targetFiles": ["com.kiloo.subwaysurf/Config.cs"],
              "patches": [{ "identifier": "STARTING_COINS", "newValue": "999999" }]
            }
            """.trimIndent()
        )

        val report = CodePatchLoader().applyForGame(ws, "com.kiloo.subwaysurf", gameDir)

        assertEquals(1, report.configFilesDiscovered)
        assertEquals(1, report.configFilesLoaded)
        assertEquals(1, report.filesVisited)
        assertEquals(1, report.filesPatched)
        assertTrue(report.errors.isEmpty(), "No errors expected for a valid unconditional patch")
    }

    @Test
    fun `codepatch for different gameId is not applied`() {
        // Regression: patches must be filtered by gameId even when expectedOldValue is absent
        val ws = Files.createTempDirectory("codepatch-gameid-filter")
        val gameDir = ws.resolve("com.kiloo.subwaysurf").also { it.createDirectories() }
        val source = gameDir.resolve("Config.cs")
        source.writeText("private const int STARTING_COINS = 500;")

        // This patch targets a DIFFERENT game
        ws.resolve("other.codepatch").writeText(
            """
            {
              "name": "OtherGameBoost",
              "gameId": "com.othergame.example",
              "targetFiles": ["com.kiloo.subwaysurf/Config.cs"],
              "patches": [{ "identifier": "STARTING_COINS", "newValue": "999999" }]
            }
            """.trimIndent()
        )

        val report = CodePatchLoader().applyForGame(ws, "com.kiloo.subwaysurf", gameDir)

        // Config discovered but not loaded for this gameId
        assertEquals(1, report.configFilesDiscovered)
        // File should be unchanged
        assertTrue(
            source.readText().contains("= 500"),
            "Patch for a different gameId must not modify the file"
        )
    }
}
