package com.smapifan.androidmodder.model

import kotlinx.serialization.Serializable

/**
 * Declares a save-data operation that a mod can perform on the app's external
 * storage directory (`/sdcard/Android/data/<gameId>/`).
 *
 * ✅ **No root required** – only the external storage path is used, which is
 * accessible to all apps without root.
 *
 * - [EXPORT] copies the game's external save data into the workspace so it can
 *   be inspected, backed up, or shared.
 * - [IMPORT] restores previously exported (and optionally hand-edited) save data
 *   from the workspace back to external storage, so the game picks it up on next
 *   launch.
 */
@Serializable
enum class SaveDataAction {
    /** Export save data from external storage into the workspace. */
    EXPORT,

    /** Import save data from the workspace back to external storage. */
    IMPORT
}
