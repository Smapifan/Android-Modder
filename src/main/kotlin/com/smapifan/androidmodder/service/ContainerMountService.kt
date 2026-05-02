// Redirects an app's save-data directories into the workspace via kernel bind-mounts inside an Android Multi-User container.
// Leitet die Spielstandverzeichnisse einer App über Kernel-Bind-Mounts in den Workspace um, innerhalb eines Android-Multi-User-Containers.

package com.smapifan.androidmodder.service

// ═════════════════════════════════════════════════════════════════════════════
//  ContainerMountService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Redirects an Android app's save-data directories inside a container user via
 * kernel **bind-mounts**.
 *
 * ## How bind-mounts work for save-path redirection
 *
 * A bind-mount makes a source directory appear at a different location in the
 * filesystem without copying any data:
 *
 * ```
 * mount --bind <sourceDir>  <targetDir>
 * ```
 *
 * The target directory (which the app reads/writes) is transparently backed by
 * the source directory.  From the app's perspective, nothing changes – it still
 * reads and writes the same paths.  From Android-Modder's perspective, all
 * game saves land in the workspace, where cheats/mods can be applied before the
 * game starts, or observed while the game is running.
 *
 * ## Mount layout
 *
 * ### Internal data (root required)
 * ```
 * <saveDir>  →  /data/user/<userId>/<packageName>/
 * ```
 * Redirects the app's internal data directory (shared preferences, databases,
 * `files/`, `cache/`) to [saveDir].
 *
 * ### External data (root required)
 * ```
 * <saveDir>  →  /sdcard/Android/data/<packageName>/
 * ```
 * Redirects the app's external-storage data directory to [saveDir].
 *
 * ## Root requirement
 *
 * `mount --bind` for paths outside the calling process's own sandbox always
 * requires root.  The device must be rooted (Magisk / SuperSU).
 *
 * ## Cleanup
 *
 * Always call [unmountSavePath] / [unmountExternalSavePath] when the game
 * session ends to avoid stale mounts.
 *
 * @param shell shell executor used for all `mount` / `umount` invocations
 */
open class ContainerMountService(
    private val shell: ShellExecutor = ShellExecutor()
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Internal data directory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bind-mounts [saveDir] over the internal data directory of [packageName]
     * inside the container identified by [userId].
     *
     * Equivalent to:
     * ```
     * mount --bind <saveDir>  /data/user/<userId>/<packageName>
     * ```
     *
     * Any files the game writes to `/data/user/<userId>/<packageName>/` will
     * land in [saveDir] instead, and any files already in [saveDir] will appear
     * to the game as its existing save data.
     *
     * @param packageName  the app's package name, e.g. `"com.gram.mergedragons"`
     * @param userId       the Android user-ID of the container (from [ContainerService])
     * @param saveDir      absolute path to the host directory to bind-mount as save storage
     * @return `true` if the mount succeeded
     */
    open fun mountSavePath(packageName: String, userId: Int, saveDir: String): Boolean {
        val target = internalDataPath(packageName, userId)
        val result = shell.execute("mount --bind \"$saveDir\" \"$target\"", asRoot = true)
        return result.success
    }

    /**
     * Removes the bind-mount from the internal data directory of [packageName]
     * inside the container identified by [userId].
     *
     * Equivalent to:
     * ```
     * umount /data/user/<userId>/<packageName>
     * ```
     *
     * @param packageName the app's package name
     * @param userId      the Android user-ID of the container
     * @return `true` if the unmount succeeded
     */
    open fun unmountSavePath(packageName: String, userId: Int): Boolean {
        val target = internalDataPath(packageName, userId)
        val result = shell.execute("umount \"$target\"", asRoot = true)
        return result.success
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  External data directory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bind-mounts [saveDir] over the external-storage data directory of [packageName].
     *
     * Equivalent to:
     * ```
     * mount --bind <saveDir>  /sdcard/Android/data/<packageName>
     * ```
     *
     * Useful for games that persist saves to external storage
     * (`/sdcard/Android/data/<pkg>/`) rather than internal storage.
     *
     * @param packageName         the app's package name
     * @param saveDir             absolute path to the host directory to bind-mount
     * @param externalStorageRoot root of external storage, default `/sdcard`
     * @return `true` if the mount succeeded
     */
    open fun mountExternalSavePath(
        packageName: String,
        saveDir: String,
        externalStorageRoot: String = "/sdcard"
    ): Boolean {
        val target = externalDataPath(packageName, externalStorageRoot)
        val result = shell.execute("mount --bind \"$saveDir\" \"$target\"", asRoot = true)
        return result.success
    }

    /**
     * Removes the bind-mount from the external-storage data directory of [packageName].
     *
     * Equivalent to:
     * ```
     * umount /sdcard/Android/data/<packageName>
     * ```
     *
     * @param packageName         the app's package name
     * @param externalStorageRoot root of external storage, default `/sdcard`
     * @return `true` if the unmount succeeded
     */
    open fun unmountExternalSavePath(
        packageName: String,
        externalStorageRoot: String = "/sdcard"
    ): Boolean {
        val target = externalDataPath(packageName, externalStorageRoot)
        val result = shell.execute("umount \"$target\"", asRoot = true)
        return result.success
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Path helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the internal data path for [packageName] inside Android user [userId]:
     * `/data/user/<userId>/<packageName>`
     */
    fun internalDataPath(packageName: String, userId: Int): String =
        "/data/user/$userId/$packageName"

    /**
     * Returns the external-storage data path for [packageName]:
     * `<externalStorageRoot>/Android/data/<packageName>`
     */
    fun externalDataPath(
        packageName: String,
        externalStorageRoot: String = "/sdcard"
    ): String = "$externalStorageRoot/Android/data/$packageName"
}
