// Parses the curated app catalog JSON and provides lookup and age-filter helper methods.
// Parst den kuratierten App-Katalog-JSON und stellt Such- und Altersfilter-Hilfsmethoden bereit.

package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.AppEntry
import kotlinx.serialization.json.Json

/**
 * Loads the optional curated app catalog and provides lookup/filter helpers.
 *
 * The catalog is purely informational – it is not an allow-list.  Any package
 * name can be launched by [GameLauncherService] regardless of whether it
 * appears in the catalog.  Unknown apps are handled generically and are never
 * blocked.
 *
 * Installation links always point to the official Play Store URL so that
 * system-level parental controls (e.g. Google Family Link) remain active.
 */
class AppCatalogService(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** Parses a JSON array of [AppEntry] objects. */
    fun parse(jsonText: String): List<AppEntry> =
        json.decodeFromString(jsonText)

    /**
     * Creates a generic [AppEntry] for any package that is not in the catalog.
     *
     * This lets callers treat catalogued and uncatalogued packages uniformly
     * without special-casing unknown apps.
     */
    fun fromPackageName(packageName: String): AppEntry =
        AppEntry(
            name        = packageName,
            packageName = packageName,
            category    = "Unknown",
            minAgeRating = 0,
            label       = packageName
        )

    /**
     * Returns all entries whose [AppEntry.minAgeRating] is at most [userAge].
     *
     * Pass [userAge] = [Int.MAX_VALUE] to get the full catalog without filtering.
     * This is a display-only filter; it does not restrict which packages can be launched.
     */
    fun filterByAge(entries: List<AppEntry>, userAge: Int): List<AppEntry> =
        entries.filter { it.minAgeRating <= userAge }

    /**
     * Looks up [packageName] in [entries], or falls back to [fromPackageName]
     * if the package is not in the catalog.
     *
     * Guarantees a non-null result for any package name.
     */
    fun findOrGeneric(entries: List<AppEntry>, packageName: String): AppEntry =
        entries.firstOrNull { it.packageName == packageName } ?: fromPackageName(packageName)

    /**
     * Returns the Play Store web URL for an entry.
     *
     * On Android this can be used with an Intent to open the Play Store app
     * directly.
     */
    fun playStoreUrl(entry: AppEntry): String =
        "https://play.google.com/store/apps/details?id=${entry.packageName}"
}
