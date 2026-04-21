package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * Represents a single app entry in the curated catalog.
 *
 * [minAgeRating] is the minimum recommended age (e.g. 0, 6, 12, 16, 18).
 * Installation always routes through the official Play Store so that
 * Family Link parental controls remain fully in effect.
 */
@Serializable
data class AppEntry(
    val name: String,
    val packageName: String,
    val category: String,
    val minAgeRating: Int
)
