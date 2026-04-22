package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * Represents a single app entry – either from the curated catalog or
 * discovered dynamically from workspace/inputs.
 *
 * [label] is the human-readable display name shown in the UI.  It defaults
 * to [name] so catalog entries that only set [name] continue to work.
 *
 * [iconPath] is an optional path to the app's icon file (e.g. a PNG extracted
 * from the APK or fetched from the Play Store metadata).  The Android UI layer
 * can load this path via `BitmapFactory` or `Glide`; JVM-side code ignores it.
 *
 * [minAgeRating] is the minimum recommended age (e.g. 0, 6, 12, 16, 18).
 * This is informational only and is **not** used as a hard access restriction –
 * any package name can be launched regardless of catalog membership or age rating.
 */
@Serializable
data class AppEntry(
    /** Identifier / internal name, e.g. `"Merge Dragons!"`. */
    val name: String,
    /** Android package name, e.g. `"com.gram.mergedragons"`. */
    val packageName: String,
    /** Category label shown in the UI, e.g. `"Games"`. */
    val category: String,
    /** Minimum recommended age; 0 means all ages. */
    val minAgeRating: Int,
    /**
     * Human-readable display label for the UI.  Defaults to [name] when not
     * explicitly set, so existing catalog JSON files require no changes.
     */
    val label: String = name,
    /**
     * Optional path to the app icon (PNG / WebP).
     *
     * `null` means no icon is available yet; the UI should show a placeholder.
     * On Android this can be set to a file path inside the app's cache dir
     * after downloading the icon from the Play Store CDN.
     */
    val iconPath: String? = null
)
