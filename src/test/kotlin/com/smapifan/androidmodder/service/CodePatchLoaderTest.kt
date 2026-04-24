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

        assertEquals(1, report.filesVisited)
        assertEquals(1, report.filesPatched)
        assertTrue(source.readText().contains("= 0.5"))
    }
}
