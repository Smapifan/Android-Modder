package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CheatDefinition
import com.smapifan.androidmodder.model.CheatOperation
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheatApplierTest {
    private val applier = CheatApplier()

    private fun makeWorkspace(vararg files: Pair<String, String>): java.nio.file.Path {
        val dir = Files.createTempDirectory("cheat-test")
        files.forEach { (relativePath, content) ->
            val file = dir.resolve(relativePath)
            file.parent.createDirectories()
            Files.writeString(file, content)
        }
        return dir
    }

    private fun cheat(field: String, op: CheatOperation, amount: Long) =
        CheatDefinition(appName = "TestGame", field = field, operation = op, amount = amount)

    // --- ADD --------------------------------------------------------------

    @Test
    fun `ADD increases field value`() {
        val dir = makeWorkspace("data/data/TestGame/files/save.dat" to "coins=500\ngems=10")
        val result = applier.apply(dir, cheat("coins", CheatOperation.ADD, 1000))
        assertEquals(1500L, result)
    }

    @Test
    fun `ADD starts from 0 when field is absent`() {
        val dir = makeWorkspace("data/data/TestGame/files/save.dat" to "lives=3")
        val result = applier.apply(dir, cheat("coins", CheatOperation.ADD, 1000))
        assertEquals(1000L, result)
    }

    // --- SUBTRACT ---------------------------------------------------------

    @Test
    fun `SUBTRACT decreases field value`() {
        val dir = makeWorkspace("data/data/TestGame/files/save.dat" to "coins=2000")
        val result = applier.apply(dir, cheat("coins", CheatOperation.SUBTRACT, 500))
        assertEquals(1500L, result)
    }

    @Test
    fun `SUBTRACT floors at 0`() {
        val dir = makeWorkspace("data/data/TestGame/files/save.dat" to "coins=100")
        val result = applier.apply(dir, cheat("coins", CheatOperation.SUBTRACT, 9999))
        assertEquals(0L, result)
    }

    // --- SET --------------------------------------------------------------

    @Test
    fun `SET replaces field value exactly`() {
        val dir = makeWorkspace("data/data/TestGame/files/save.dat" to "coins=100\ngems=5")
        val result = applier.apply(dir, cheat("gems", CheatOperation.SET, 9999))
        assertEquals(9999L, result)
    }

    @Test
    fun `SET writes field even when not previously present`() {
        val dir = makeWorkspace("data/data/TestGame/files/save.dat" to "lives=3")
        val result = applier.apply(dir, cheat("coins", CheatOperation.SET, 500))
        assertEquals(500L, result)
    }

    // --- persistence ------------------------------------------------------

    @Test
    fun `apply persists change to disk`() {
        val dir  = makeWorkspace("data/data/TestGame/save.dat" to "coins=100\nlives=3")
        val file = dir.resolve("data/data/TestGame/save.dat")

        applier.apply(dir, cheat("coins", CheatOperation.ADD, 900))

        val fields = applier.readFields(file)
        assertEquals("1000", fields["coins"])
        assertEquals("3", fields["lives"])
    }

    @Test
    fun `apply searches nested subdirectory`() {
        val dir = makeWorkspace(
            "data/data/TestGame/files/shared_prefs/game.dat" to "score=42"
        )
        val result = applier.apply(dir, cheat("score", CheatOperation.ADD, 100))
        assertEquals(142L, result)
    }

    // --- error cases ------------------------------------------------------

    @Test
    fun `throws when workspace directory does not exist`() {
        val dir = java.nio.file.Path.of("/tmp/nonexistent-${System.nanoTime()}")
        assertFailsWith<IllegalArgumentException> {
            applier.apply(dir, cheat("coins", CheatOperation.ADD, 100))
        }
    }

    @Test
    fun `throws when field is not found anywhere in workspace`() {
        val dir = makeWorkspace("data/data/TestGame/save.dat" to "lives=3")
        assertFailsWith<IllegalStateException> {
            applier.apply(dir, cheat("coins", CheatOperation.ADD, 100))
        }
    }

    // --- readFields / writeFields -----------------------------------------

    @Test
    fun `readFields skips blank lines and comments`() {
        val file = Files.createTempFile("fields", ".dat")
        Files.writeString(file, "# comment\n\ncoins=10\nlives=3")
        val fields = applier.readFields(file)
        assertEquals(mapOf("coins" to "10", "lives" to "3"), fields)
    }

    @Test
    fun `writeFields produces key=value lines`() {
        val file = Files.createTempFile("write", ".dat")
        applier.writeFields(file, linkedMapOf("coins" to "999", "gems" to "5"))
        assertEquals("coins=999\ngems=5", file.readText())
    }
}
