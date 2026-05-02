// Enum controlling when a mod's patches are applied: on game launch, on user demand, or on autosave interval.
// Aufzählung, die steuert, wann Mod-Patches angewendet werden: beim Spielstart, auf Benutzeranfrage oder im Autosave-Intervall.

package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * Controls *when* a mod's patches are applied during a game session.
 *
 * ```
 * ┌──────────────┬──────────────────────────────────────────────────────────┐
 * │ ON_LAUNCH    │ Applied once, right before the game process starts.      │
 * │              │ This is the classic, always-safe mode: modify the        │
 * │              │ workspace copy of the save file, then launch the game.   │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ ON_DEMAND    │ Applied when the user taps the corresponding button in   │
 * │              │ the floating overlay HUD while the game is running.      │
 * │              │ Ideal for games with frequent autosaves where you want   │
 * │              │ manual control over *when* patches fire.                 │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ ON_AUTOSAVE  │ Applied automatically on a fixed interval (default 30 s) │
 * │              │ while the game process is alive.  The game picks up the  │
 * │              │ changed values on its next autosave cycle.               │
 * └──────────────┴──────────────────────────────────────────────────────────┘
 * ```
 */
@Serializable
enum class TriggerMode {

    /**
     * Patches are applied **once**, immediately before the game launches.
     *
     * This is the default and requires no overlay permission.
     * The game reads the pre-modified save file on first load.
     */
    ON_LAUNCH,

    /**
     * Patches are applied **on user request** via an overlay button tap.
     *
     * Requires the `SYSTEM_ALERT_WINDOW` permission so that the floating
     * HUD can be drawn over the game.  The game picks up the changes on
     * its next autosave cycle.
     */
    ON_DEMAND,

    /**
     * Patches are applied **repeatedly** (every [ModOverlayService.autosaveIntervalMs])
     * for as long as the game process is alive.
     *
     * Useful for games that overwrite save files frequently; the patched
     * values are continuously re-applied so the override stays in effect.
     */
    ON_AUTOSAVE
}
