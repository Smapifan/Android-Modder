package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CodePatchDefinition
import com.smapifan.androidmodder.model.CodePatchDefinition.Language
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the universal [CodePatcher].  Each test covers a different
 * language / syntax to prove that the same patch definition patches *all*
 * mainstream game source and decompiled-code formats.
 */
class CodePatcherTest {

    private val patcher = CodePatcher()

    // ─── Helper ────────────────────────────────────────────────────────────

    private fun patchText(input: String, patch: CodePatchDefinition, ext: String = ""): PatchOutcome =
        patcher.patchText(input, patch, fileExtension = ext)

    // ─── C# (screenshot example) ───────────────────────────────────────────

    @Test fun `patches C# private const float`() {
        val source = "private const float K_CHANCE_OF_DRAGON_STAR = 0.05;"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K_CHANCE_OF_DRAGON_STAR", newValue = "0.5"),
            ext = "cs")
        assertIs<PatchOutcome.Patched>(result)
        assertEquals("0.05", result.oldValue)
        assertEquals(1, result.occurrences)
        assertEquals("private const float K_CHANCE_OF_DRAGON_STAR = 0.5;", result.patchedContent)
    }

    // ─── Java ──────────────────────────────────────────────────────────────

    @Test fun `patches Java public static final float`() {
        val source = "public static final float MAX_HP = 100.0f;"
        val result = patchText(source,
            CodePatchDefinition(identifier = "MAX_HP", newValue = "9999.0f"),
            ext = "java")
        assertIs<PatchOutcome.Patched>(result)
        assertEquals("public static final float MAX_HP = 9999.0f;", result.patchedContent)
    }

    // ─── Kotlin ────────────────────────────────────────────────────────────

    @Test fun `patches Kotlin const val`() {
        val source = "const val MAX_COINS: Long = 1000L"
        val result = patchText(source,
            CodePatchDefinition(identifier = "MAX_COINS", newValue = "999999L"),
            ext = "kt")
        assertIs<PatchOutcome.Patched>(result)
        assertTrue(result.patchedContent.endsWith("999999L"))
    }

    // ─── C / C++ ───────────────────────────────────────────────────────────

    @Test fun `patches C++ constexpr`() {
        val source = "constexpr float K_CHANCE = 0.05f;"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K_CHANCE", newValue = "0.5f"),
            ext = "cpp")
        assertIs<PatchOutcome.Patched>(result)
        assertEquals("constexpr float K_CHANCE = 0.5f;", result.patchedContent)
    }

    // ─── Python ────────────────────────────────────────────────────────────

    @Test fun `patches Python assignment`() {
        val source = "K_CHANCE_OF_DRAGON_STAR = 0.05\nnext_line = 1\n"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K_CHANCE_OF_DRAGON_STAR", newValue = "0.5", language = Language.PYTHON))
        assertIs<PatchOutcome.Patched>(result)
        assertTrue(result.patchedContent.contains("K_CHANCE_OF_DRAGON_STAR = 0.5"))
        assertTrue(result.patchedContent.contains("next_line = 1"))
    }

    // ─── Lua ───────────────────────────────────────────────────────────────

    @Test fun `patches Lua local`() {
        val source = "local K_CHANCE = 0.05\n"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K_CHANCE", newValue = "0.5", language = Language.LUA))
        assertIs<PatchOutcome.Patched>(result)
        assertTrue(result.patchedContent.contains("local K_CHANCE = 0.5"))
    }

    // ─── Smali (Dalvik bytecode) ───────────────────────────────────────────

    @Test fun `patches Smali field`() {
        val source = ".field private static final K_CHANCE:F = 0.05f"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K_CHANCE", newValue = "0.5f", language = Language.SMALI))
        assertIs<PatchOutcome.Patched>(result)
        assertTrue(result.patchedContent.endsWith("= 0.5f"))
    }

    // ─── JavaScript / TypeScript ───────────────────────────────────────────

    @Test fun `patches JavaScript const`() {
        val source = "const K_CHANCE = 0.05;"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K_CHANCE", newValue = "0.5"),
            ext = "js")
        assertIs<PatchOutcome.Patched>(result)
        assertEquals("const K_CHANCE = 0.5;", result.patchedContent)
    }

    // ─── Rust ──────────────────────────────────────────────────────────────

    @Test fun `patches Rust const`() {
        val source = "const K_CHANCE: f32 = 0.05;"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K_CHANCE", newValue = "0.5"),
            ext = "rs")
        assertIs<PatchOutcome.Patched>(result)
        assertTrue(result.patchedContent.endsWith("= 0.5;"))
    }

    // ─── Expected-value mismatch guard ─────────────────────────────────────

    @Test fun `expectedOldValue mismatch leaves file unchanged`() {
        val source = "const float K = 0.1f;"
        val result = patchText(source,
            CodePatchDefinition(identifier = "K", newValue = "0.5", expectedOldValue = "0.05"),
            ext = "cs")
        assertIs<PatchOutcome.ValueMismatch>(result)
        assertEquals("0.1f", result.actual)
    }

    // ─── String-literal patching ───────────────────────────────────────────

    @Test fun `patches string literal`() {
        val source = """const HERO = "Conan";"""
        val result = patchText(source,
            CodePatchDefinition(identifier = "HERO", newValue = "\"Xena\""),
            ext = "js")
        assertIs<PatchOutcome.Patched>(result)
        assertEquals("""const HERO = "Xena";""", result.patchedContent)
    }

    // ─── Not-found ─────────────────────────────────────────────────────────

    @Test fun `returns NotFound when identifier is absent`() {
        val source = "const float other = 1.0f;"
        val result = patchText(source,
            CodePatchDefinition(identifier = "MISSING", newValue = "0.5"),
            ext = "cs")
        assertEquals(PatchOutcome.NotFound, result)
    }

    // ─── File round-trip ───────────────────────────────────────────────────

    @Test fun `writes patched content to disk`() {
        val tmp: Path = Files.createTempFile("codepatcher", ".cs")
        tmp.writeText("private const float K_CHANCE_OF_DRAGON_STAR = 0.05;")
        val outcome = patcher.patch(tmp,
            CodePatchDefinition(identifier = "K_CHANCE_OF_DRAGON_STAR", newValue = "0.5"))
        assertIs<PatchOutcome.Patched>(outcome)
        assertEquals("private const float K_CHANCE_OF_DRAGON_STAR = 0.5;", tmp.readText())
        Files.deleteIfExists(tmp)
    }

    // ─── Tree walk ─────────────────────────────────────────────────────────

    @Test fun `patchTree patches every matching file under a directory`() {
        val root = Files.createTempDirectory("codepatcher-tree")
        val a = root.resolve("A.cs").also { it.writeText("const float K = 0.05f;") }
        val b = root.resolve("B.cs").also { it.writeText("const float K = 0.05f;") }
        val c = root.resolve("C.txt").also { it.writeText("irrelevant content") }
        val outcomes = patcher.patchTree(root,
            CodePatchDefinition(identifier = "K", newValue = "0.5f"),
            extensions = setOf("cs"))
        assertEquals(2, outcomes.size)
        assertTrue(a.readText().contains("= 0.5f"))
        assertTrue(b.readText().contains("= 0.5f"))
        assertTrue(c.readText().contains("irrelevant"))
    }

    // ─── Language auto-detection ───────────────────────────────────────────

    @Test fun `detects language from file extension`() {
        assertEquals(Language.C_SHARP, patcher.detectLanguage("cs"))
        assertEquals(Language.KOTLIN,  patcher.detectLanguage("kt"))
        assertEquals(Language.SMALI,   patcher.detectLanguage("smali"))
        assertEquals(Language.PYTHON,  patcher.detectLanguage("py"))
        assertEquals(Language.LUA,     patcher.detectLanguage("lua"))
        assertEquals(Language.AUTO,    patcher.detectLanguage("unknown"))
    }
}
