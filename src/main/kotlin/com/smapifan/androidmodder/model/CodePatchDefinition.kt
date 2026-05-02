// Describes a single universal source-code or bytecode constant patch applicable across all major programming languages.
// Beschreibt einen universellen Quellcode- oder Bytecode-Konstanten-Patch, der für alle gängigen Programmiersprachen gilt.

package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * Describes a single universal source-code / bytecode / binary constant patch
 * that the [com.smapifan.androidmodder.service.CodePatcher] can apply to a
 * file of *any* programming language.
 *
 * ## Philosophy: every game is patchable
 *
 * The same mod file format works for **every syntax** the player might throw
 * at Android-Modder.  Whether the dumped source/decompiled code is C#, Java,
 * Kotlin, C/C++, Smali (Dalvik bytecode), JavaScript / TypeScript, Python,
 * Rust, Go, Swift, Dart, Lua, PHP or Ruby – the patcher locates the named
 * identifier and replaces its literal value in place, preserving the exact
 * whitespace, modifiers (`private`, `const`, `readonly`, `static`, `final`,
 * `val`, `var`, `let`, `public`, …) and the original line terminator.
 *
 * ## Example
 *
 * Source line (from the screenshot):
 * ```
 * private const float K_CHANCE_OF_DRAGON_STAR = 0.05;
 * ```
 *
 * Patch:
 * ```json
 * {
 *   "identifier": "K_CHANCE_OF_DRAGON_STAR",
 *   "newValue":   "0.5"
 * }
 * ```
 *
 * After patching:
 * ```
 * private const float K_CHANCE_OF_DRAGON_STAR = 0.5;
 * ```
 *
 * The same patch definition also works on:
 * - Java:   `public static final float K_CHANCE_OF_DRAGON_STAR = 0.05f;`
 * - Kotlin: `const val K_CHANCE_OF_DRAGON_STAR: Float = 0.05f`
 * - C++:    `constexpr float K_CHANCE_OF_DRAGON_STAR = 0.05f;`
 * - Smali:  `.field private static final K_CHANCE_OF_DRAGON_STAR:F = 0.05f`
 * - JS/TS:  `const K_CHANCE_OF_DRAGON_STAR = 0.05;`
 * - Python: `K_CHANCE_OF_DRAGON_STAR = 0.05`
 * - Rust:   `const K_CHANCE_OF_DRAGON_STAR: f32 = 0.05;`
 * - Go:     `const KChanceOfDragonStar = 0.05`
 * - Lua:    `local K_CHANCE_OF_DRAGON_STAR = 0.05`
 *
 * @param identifier Name of the constant/variable to locate (case-sensitive).
 * @param newValue   Replacement literal as a **string** (so that 0.5, 0.5f,
 *                   `0.5F`, `500L`, `"hero"`, `true`, hex `0xFF` and similar
 *                   language-specific suffixes all round-trip correctly).
 * @param expectedOldValue  Optional safety check – when non-`null` the patch
 *                   is only applied if the current literal value matches
 *                   exactly.  Protects against patching the wrong version
 *                   of a game after an update changed the default value.
 * @param language   Optional hint for the patcher.  When [Language.AUTO]
 *                   (default) the patcher tries the generic universal regex
 *                   that works for virtually every C-style language, falling
 *                   back to language-specific patterns for Smali, Python
 *                   and Lua when needed.
 */
@Serializable
data class CodePatchDefinition(
    val identifier: String,
    val newValue: String,
    val expectedOldValue: String? = null,
    val language: Language = Language.AUTO
) {
    /** Supported source / decompiled languages. */
    @Serializable
    enum class Language {
        AUTO,
        C_SHARP, JAVA, KOTLIN, C, CPP,
        SMALI,
        JAVASCRIPT, TYPESCRIPT,
        PYTHON, RUST, GO, SWIFT, DART,
        LUA, PHP, RUBY
    }
}
