package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.AppEntry
import kotlinx.serialization.json.Json

/**
 * Loads the curated app catalog and provides age-filtered views.
 *
 * Installation is always performed via the official Play Store URL so that
 * Google Family Link parental controls apply in full – this service only
 * generates the link, it does not install anything itself.
 */
class AppCatalogService(private val json: Json = Json { ignoreUnknownKeys = true }) {

    fun parse(jsonText: String): List<AppEntry> =
        json.decodeFromString(jsonText)

    /**
     * Returns all entries whose [AppEntry.minAgeRating] is at most [userAge].
     * Pass [userAge] = [Int.MAX_VALUE] to retrieve the full catalog.
     */
    fun filterByAge(entries: List<AppEntry>, userAge: Int): List<AppEntry> =
        entries.filter { it.minAgeRating <= userAge }

    /**
     * Returns the Play Store web URL for an entry.
     * On Android this can be used with an Intent to open the Play Store app
     * directly, which preserves all Family Link approval flows.
     */
    fun playStoreUrl(entry: AppEntry): String =
        "https://play.google.com/store/apps/details?id=${entry.packageName}"
}
