package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * Describes a single **button** shown in the floating overlay HUD.
 *
 * When the user taps this button, the [ModOverlayService] applies only the
 * patches from the parent [ModDefinition] whose [ModPatch.field] is listed in
 * [patchFields].  This lets one mod expose fine-grained controls, e.g.:
 *
 * ```json
 * "overlayActions": [
 *   { "label": "+10k Coins", "patchFields": ["coins"] },
 *   { "label": "Max Gems",   "patchFields": ["gems"]  }
 * ]
 * ```
 *
 * Only patches listed in [patchFields] are triggered – the rest of the mod's
 * patches remain untouched until their own button (or trigger mode) fires.
 *
 * @param label       Human-readable text shown on the overlay button.
 * @param patchFields Names of [ModPatch.field]s from the parent mod that this
 *                    button should activate.
 */
@Serializable
data class OverlayAction(
    /** Human-readable button label rendered in the overlay HUD. */
    val label: String,

    /**
     * Subset of patch field names (from the parent [ModDefinition]) that
     * this button activates.  Fields not listed here are left unchanged.
     */
    val patchFields: List<String>
)
