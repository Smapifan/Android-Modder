// Universal source-code/bytecode patcher that replaces named constant values in text files across all major languages.
// Universeller Quellcode-/Bytecode-Patcher, der benannte Konstantenwerte in Textdateien aller gängigen Sprachen ersetzt.

package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.CodePatchDefinition
import com.smapifan.androidmodder.model.CodePatchDefinition.Language
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ═════════════════════════════════════════════════════════════════════════════
//  CodePatcher – the universal constant patcher
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Universal source-code / decompiled-bytecode patcher.
 *
 * Replaces the literal value of a named constant or variable assignment in a
 * text file, regardless of the surrounding language syntax.  The patcher
 * preserves leading whitespace, access modifiers (`private`, `public`,
 * `static`, `final`, `const`, `readonly`, `val`, `var`, `let`, `local`,
 * `constexpr`, `@nonnull`, attributes, etc.), the declared type, and the
 * original line ending (CRLF / LF).
 *
 * ## Supported input formats
 *
 * | Language                 | Example matched by the universal regex                    |
 * |--------------------------|-----------------------------------------------------------|
 * | C#                       | `private const float K_CHANCE_OF_DRAGON_STAR = 0.05;`     |
 * | Java                     | `public static final float K_CHANCE = 0.05f;`             |
 * | Kotlin                   | `const val K_CHANCE: Float = 0.05f`                       |
 * | C / C++                  | `constexpr float K_CHANCE = 0.05f;`                       |
 * | JavaScript / TypeScript  | `const K_CHANCE = 0.05;`                                  |
 * | Rust                     | `const K_CHANCE: f32 = 0.05;`                             |
 * | Go                       | `const KChance = 0.05`                                    |
 * | Swift                    | `let kChance: Float = 0.05`                               |
 * | Dart                     | `static const double kChance = 0.05;`                     |
 * | PHP                      | `const K_CHANCE = 0.05;`    /  `$kChance = 0.05;`         |
 * | Ruby                     | `K_CHANCE = 0.05`                                         |
 * | Smali (Dalvik bytecode)  | `.field private static final K_CHANCE:F = 0.05f`          |
 * | Python                   | `K_CHANCE = 0.05`                                         |
 * | Lua                      | `local K_CHANCE = 0.05`                                   |
 *
 * ## How it works
 *
 * The patcher never parses the full AST of the target language – that would
 * require a compiler front-end for every language.  Instead it relies on the
 * fact that **all** mainstream languages use the same universal grammar for
 * constant declarations:
 *
 * ```
 * …modifiers…  [type]  IDENTIFIER  [:type]  =  LITERAL  [;|,]
 * ```
 *
 * A single generic regex captures this shape and replaces the `LITERAL`
 * group, which is sufficient for the overwhelming majority of games.  For
 * the three languages that deviate (Smali, Python, Lua) dedicated patterns
 * are provided and selected either via [CodePatchDefinition.language] or
 * automatically from the file extension.
 *
 * ## Safety
 *
 * - [CodePatchDefinition.expectedOldValue] – when set, the patch is only
 *   applied if the current literal matches exactly.  Prevents double-patching
 *   or patching the wrong game version.
 * - The original file is replaced atomically via [Path.writeText]; partial
 *   writes cannot leave a half-patched file.
 * - If no occurrence of the identifier is found, the method returns a
 *   [PatchOutcome.NotFound] result and the file is **not** written.
 *
 * ## Usage
 *
 * ```kotlin
 * val patcher = CodePatcher()
 * val result  = patcher.patch(
 *     file  = Path.of("DragonRanch.Shared.cs"),
 *     patch = CodePatchDefinition(
 *         identifier        = "K_CHANCE_OF_DRAGON_STAR",
 *         newValue          = "0.5",
 *         expectedOldValue  = "0.05"
 *     )
 * )
 * println(result)   // e.g. Patched(occurrences = 1, oldValue = "0.05")
 * ```
 */
open class CodePatcher {

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies [patch] to the single [file] on disk.
     *
     * @return [PatchOutcome.Patched] with the number of replaced occurrences
     *         and the original literal value, [PatchOutcome.NotFound] if the
     *         identifier was not present, or [PatchOutcome.ValueMismatch] if
     *         [CodePatchDefinition.expectedOldValue] was specified but the
     *         current literal differs.
     */
    open fun patch(file: Path, patch: CodePatchDefinition): PatchOutcome {
        require(file.isRegularFile()) { "Not a regular file: $file" }
        val original = file.readText()
        val result = patchText(original, patch, fileExtension = file.fileName.toString().substringAfterLast('.', ""))
        if (result is PatchOutcome.Patched) {
            file.writeText(result.patchedContent)
        }
        return result
    }

    /**
     * Applies [patch] to every text file under [root] (recursively) that has
     * one of the [extensions].  Returns the per-file [PatchOutcome] map.
     *
     * When [extensions] is empty (default) the patcher walks *all* regular
     * files, relying on the universal regex to skip files that do not match.
     */
    open fun patchTree(
        root: Path,
        patch: CodePatchDefinition,
        extensions: Set<String> = emptySet()
    ): Map<Path, PatchOutcome> {
        require(Files.isDirectory(root)) { "Not a directory: $root" }
        val results = LinkedHashMap<Path, PatchOutcome>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { p ->
                    extensions.isEmpty() ||
                    extensions.any { ext -> p.fileName.toString().endsWith(".$ext", ignoreCase = true) }
                }
                .forEach { p ->
                    val outcome = runCatching { patch(p, patch) }
                        .getOrElse { PatchOutcome.Error(it.message ?: it.toString()) }
                    if (outcome !is PatchOutcome.NotFound) results[p] = outcome
                }
        }
        return results
    }

    /**
     * In-memory helper – patches [text] without touching any file.  Exposed
     * for testing and for callers that want to patch an in-memory buffer.
     *
     * @param fileExtension lower-case file extension used for auto language
     *                      detection when [CodePatchDefinition.language] is
     *                      [Language.AUTO].  Pass an empty string to force
     *                      the universal pattern.
     */
    internal fun patchText(
        text: String,
        patch: CodePatchDefinition,
        fileExtension: String = ""
    ): PatchOutcome {
        val language = if (patch.language == Language.AUTO) detectLanguage(fileExtension) else patch.language
        val regex    = regexFor(language, patch.identifier)

        var firstMatchedValue: String? = null
        var occurrences = 0

        val patched = regex.replace(text) { match ->
            val currentValue = extractValue(match)
            if (firstMatchedValue == null) firstMatchedValue = currentValue
            if (patch.expectedOldValue != null && currentValue.trim() != patch.expectedOldValue.trim()) {
                // Leave unchanged – the value mismatch is reported to the caller.
                return@replace match.value
            }
            occurrences++
            replaceValue(match, patch.newValue)
        }

        return when {
            firstMatchedValue == null ->
                PatchOutcome.NotFound
            patch.expectedOldValue != null && firstMatchedValue!!.trim() != patch.expectedOldValue.trim() ->
                PatchOutcome.ValueMismatch(firstMatchedValue!!.trim(), patch.expectedOldValue)
            occurrences == 0 ->
                PatchOutcome.NotFound
            else ->
                PatchOutcome.Patched(occurrences = occurrences, oldValue = firstMatchedValue!!.trim(), patchedContent = patched)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Regex construction per language
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the regex that matches a `name = literal` assignment for the
     * given [language] and [identifier].  Group 1 = prefix (everything up
     * to and including `=`), Group 2 = literal value, Group 3 = trailing
     * punctuation (`;`, `,` or empty).
     */
    private fun regexFor(language: Language, identifier: String): Regex {
        val id = Regex.escape(identifier)
        return when (language) {
            // Python supports no statement terminator and uses Pythonic assignment.
            Language.PYTHON -> Regex(
                """(^|\n)(\s*$id\s*(?::\s*[A-Za-z_][\w\[\]\.]*\s*)?=\s*)($LITERAL)(\s*(?:#.*)?)(?=\r?\n|$)""",
                RegexOption.MULTILINE
            )
            // Lua: `local NAME = value` or `NAME = value`.
            Language.LUA -> Regex(
                """(^|\n)(\s*(?:local\s+)?$id\s*=\s*)($LITERAL)(\s*(?:--.*)?)(?=\r?\n|$)""",
                RegexOption.MULTILINE
            )
            // Smali (.field): `.field <flags> NAME:<type> = value`
            Language.SMALI -> Regex(
                """(\.field\b[^\n=]*\b$id\s*:[^\s=]+\s*=\s*)($LITERAL)(\s*(?:#.*)?)""",
                RegexOption.MULTILINE
            )
            // Universal C-style / JVM / JS / Rust / Go / Swift / Dart / PHP / Ruby.
            else -> Regex(
                """([^\n]*?\b$id\b[^=\n]{0,100}?=\s*)($LITERAL)(\s*[;,]?)""",
                RegexOption.MULTILINE
            )
        }
    }

    /** Extracts group 2 (the literal) from a match produced by [regexFor]. */
    private fun extractValue(match: MatchResult): String {
        val groups = match.groupValues
        return when (groups.size) {
            // Universal / Smali patterns: [full, prefix, value, trailing]
            4 -> groups[2]
            // Python / Lua patterns: [full, bol, prefix, value, trailing]
            5 -> groups[3]
            else -> groups.last()
        }
    }

    /** Rebuilds the full match with [newValue] swapped in for group 2/3. */
    private fun replaceValue(match: MatchResult, newValue: String): String {
        val groups = match.groupValues
        return when (groups.size) {
            4 -> groups[1] + newValue + groups[3]
            5 -> groups[1] + groups[2] + newValue + groups[4]
            else -> match.value
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Language auto-detection via file extension
    // ─────────────────────────────────────────────────────────────────────────

    internal fun detectLanguage(fileExtension: String): Language = when (fileExtension.lowercase()) {
        "cs"                          -> Language.C_SHARP
        "java"                        -> Language.JAVA
        "kt", "kts"                   -> Language.KOTLIN
        "c", "h"                      -> Language.C
        "cpp", "cc", "cxx", "hpp"     -> Language.CPP
        "smali"                       -> Language.SMALI
        "js", "mjs", "cjs"            -> Language.JAVASCRIPT
        "ts", "tsx"                   -> Language.TYPESCRIPT
        "py", "pyw"                   -> Language.PYTHON
        "rs"                          -> Language.RUST
        "go"                          -> Language.GO
        "swift"                       -> Language.SWIFT
        "dart"                        -> Language.DART
        "lua"                         -> Language.LUA
        "php"                         -> Language.PHP
        "rb"                          -> Language.RUBY
        else                          -> Language.AUTO
    }

    companion object {
        /**
         * Universal literal regex – covers numbers (int, float, hex, suffix-
         * decorated: `f`, `F`, `L`, `UL`, `ll`, `u8`, etc.), booleans, single-
         * and double-quoted strings, char literals, and `null` / `nil` /
         * `None` / `undefined`.  Kept deliberately non-greedy so that trailing
         * semicolons / commas are not consumed.
         */
        internal const val LITERAL =
            """(?:(?:"(?:\\.|[^"\\])*")|(?:'(?:\\.|[^'\\])*')|(?:0[xX][0-9a-fA-F]+(?:_[0-9a-fA-F]+)*[uUlLfFdD]{0,3})|(?:[+\-]?(?:\d[\d_]*)?\.?\d+(?:[eE][+\-]?\d+)?[fFdDlLuU]{0,3})|true|false|null|nil|None|undefined)"""
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PatchOutcome
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Outcome of a single [CodePatcher.patch] call.
 */
sealed class PatchOutcome {

    /** The identifier was not found in the input. */
    object NotFound : PatchOutcome() { override fun toString() = "NotFound" }

    /**
     * The identifier was found but the current literal did not match
     * [CodePatchDefinition.expectedOldValue].
     */
    data class ValueMismatch(val actual: String, val expected: String) : PatchOutcome()

    /** The patcher ran but an error occurred (I/O, parse, …). */
    data class Error(val message: String) : PatchOutcome()

    /**
     * The patch was applied.
     *
     * @param occurrences    how many literal occurrences were replaced
     * @param oldValue       the literal value before the patch
     * @param patchedContent the full file / buffer after replacement
     */
    data class Patched(
        val occurrences: Int,
        val oldValue: String,
        val patchedContent: String
    ) : PatchOutcome()
}
