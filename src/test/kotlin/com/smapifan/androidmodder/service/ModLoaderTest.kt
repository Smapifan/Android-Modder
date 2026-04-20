package com.smapifan.androidmodder.service

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModLoaderTest {
    private val loader = ModLoader()

    private fun makeAppWorkspace(vararg files: Pair<String, String>): java.nio.file.Path {
        val dir = Files.createTempDirectory("modloader-test")
        files.forEach { (relativePath, content) ->
            val file = dir.resolve(relativePath)
            file.parent.createDirectories()
            Files.writeString(file, content)
        }
        return dir
    }

    @Test
    fun `load parses mod definition from file`() {
        val modFile = Files.createTempFile("InfiniteCoins", ".mod")
        Files.writeString(modFile, """
            {
              "name": "InfiniteCoins",
              "gameId": "MergeDragons",
              "description": "Adds 10000 coins",
              "patches": [
                { "field": "coins", "operation": "ADD", "amount": 10000 }
              ]
            }
        """.trimIndent())

        val mod = loader.load(modFile)

        assertEquals("InfiniteCoins", mod.name)
        assertEquals("MergeDragons", mod.gameId)
        assertEquals(1, mod.patches.size)
        assertEquals("coins", mod.patches.first().field)
        assertEquals(10000L, mod.patches.first().amount)
    }

    @Test
    fun `load handles mod with empty patches list`() {
        val modFile = Files.createTempFile("EmptyMod", ".mod")
        Files.writeString(modFile, """{"name":"Empty","gameId":"AnyGame","patches":[]}""")

        val mod = loader.load(modFile)
        assertTrue(mod.patches.isEmpty())
    }

    @Test
    fun `applyMod applies single ADD patch`() {
        val appDir = makeAppWorkspace("data/data/MergeDragons/save.dat" to "coins=500\ngems=10")
        val modFile = Files.createTempFile("Coins", ".mod")
        Files.writeString(modFile, """
            {
              "name": "MoreCoins",
              "gameId": "MergeDragons",
              "patches": [
                { "field": "coins", "operation": "ADD", "amount": 1000 }
              ]
            }
        """.trimIndent())

        val results = loader.applyMod(loader.load(modFile), appDir)

        assertEquals(1500L, results["coins"])
    }

    @Test
    fun `applyMod applies multiple patches`() {
        val appDir = makeAppWorkspace("data/data/Game/save.dat" to "coins=100\ngems=5\nlives=3")
        val modFile = Files.createTempFile("MultiPatch", ".mod")
        Files.writeString(modFile, """
            {
              "name": "BigMod",
              "gameId": "Game",
              "patches": [
                { "field": "coins", "operation": "ADD",      "amount": 900  },
                { "field": "gems",  "operation": "SET",      "amount": 999  },
                { "field": "lives", "operation": "SUBTRACT", "amount": 1    }
              ]
            }
        """.trimIndent())

        val results = loader.applyMod(loader.load(modFile), appDir)

        assertEquals(1000L, results["coins"])
        assertEquals(999L,  results["gems"])
        assertEquals(2L,    results["lives"])
    }

    @Test
    fun `applyMod returns all patched fields`() {
        val appDir = makeAppWorkspace("data/data/Game/save.dat" to "a=1\nb=2")
        val modFile = Files.createTempFile("TwoPatch", ".mod")
        Files.writeString(modFile, """
            {
              "name": "TwoPatch","gameId":"Game",
              "patches": [
                {"field":"a","operation":"ADD","amount":10},
                {"field":"b","operation":"ADD","amount":20}
              ]
            }
        """.trimIndent())

        val results = loader.applyMod(loader.load(modFile), appDir)
        assertEquals(setOf("a", "b"), results.keys)
        assertEquals(11L, results["a"])
        assertEquals(22L, results["b"])
    }
}
