package com.smapifan.androidmodder.service

// ═════════════════════════════════════════════════════════════════════════════
//  ContainerService
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Manages lightweight Android containers using Android's built-in **Multi-User**
 * system (`pm create-user` / `pm remove-user`).
 *
 * ## Why Multi-User instead of a full emulator?
 *
 * Android's Multi-User feature gives each user their own isolated `/data/user/<id>/`
 * directory tree, their own external-storage namespace, and fully separate app
 * installations.  Because the container shares the **same kernel and the same
 * Android OS image** as the host, it consumes only ~30–80 MB of RAM – a fraction
 * of what a hardware emulator requires.
 *
 * ## Root requirement
 *
 * All `pm` commands that create/remove users or install APKs for other users must
 * be run with root (`su -c`).  The device must be rooted (Magisk / SuperSU) and
 * Android-Modder must have been granted root permission.
 *
 * ## Typical lifecycle
 *
 * ```
 * val svc = ContainerService(shell)
 *
 * // 1. Create the container once
 * val userId = svc.createContainer("MyContainer")  // e.g. 11
 *
 * // 2. Install an APK into the container
 * svc.installApk("/sdcard/Download/game.apk", userId)
 *
 * // 3. Launch via GameLauncherService with containerId = userId
 * //    (GameLauncherService appends --user <userId> to am start)
 *
 * // 4. When no longer needed, clean up
 * svc.removeContainer(userId)
 * ```
 *
 * @param shell shell executor used for all `pm` and `am` invocations
 */
open class ContainerService(
    private val shell: ShellExecutor = ShellExecutor()
) {

    // ─────────────────────────────────────────────────────────────────────────
    //  Container lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new Android user (container) with the given [name].
     *
     * Runs `pm create-user <name>` with root and parses the user-ID from the
     * output line `"Success: created user id <id>"`.
     *
     * @param name human-readable label for the new user, e.g. `"AndroidModder"`
     * @return the new Android user-ID (≥ 10), or `null` if creation failed
     */
    open fun createContainer(name: String): Int? {
        val result = shell.execute("pm create-user \"$name\"", asRoot = true)
        if (!result.success) return null
        // Android prints: "Success: created user id 11"
        return USER_ID_REGEX.find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Removes the Android user (container) identified by [userId].
     *
     * Runs `pm remove-user <userId>` with root.  All app data, installed APKs,
     * and external-storage files belonging to this user are deleted by Android.
     *
     * @param userId the Android user-ID returned by [createContainer]
     * @return `true` if the user was removed successfully
     */
    open fun removeContainer(userId: Int): Boolean {
        val result = shell.execute("pm remove-user $userId", asRoot = true)
        return result.success
    }

    /**
     * Returns a list of all Android user-IDs currently present on the device,
     * including the primary user (0) and any containers created by this service.
     *
     * Parses the output of `pm list users`.
     *
     * @return sorted list of user-IDs, or an empty list on error
     */
    open fun listContainers(): List<Int> {
        val result = shell.execute("pm list users", asRoot = true)
        if (!result.success) return emptyList()
        // Each line: "	UserInfo{<id>:<name>:<flags>} running"
        return LIST_USER_ID_REGEX.findAll(result.stdout)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .sorted()
            .toList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APK management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Installs an APK into the container identified by [userId].
     *
     * Runs `pm install --user <userId> <apkPath>` with root so the APK is only
     * visible inside the container and does not appear in the primary user's
     * app drawer.
     *
     * @param apkPath absolute path on-device to the APK file, e.g. `"/sdcard/Download/game.apk"`
     * @param userId  the Android user-ID of the target container
     * @return `true` if the installation succeeded (`pm` exited with code 0)
     */
    open fun installApk(apkPath: String, userId: Int): Boolean {
        val result = shell.execute("pm install --user $userId \"$apkPath\"", asRoot = true)
        return result.success
    }

    /**
     * Uninstalls a package from the container identified by [userId] without
     * removing it from other users.
     *
     * Runs `pm uninstall --user <userId> <packageName>` with root.
     *
     * @param packageName the package to remove, e.g. `"com.gram.mergedragons"`
     * @param userId      the Android user-ID of the target container
     * @return `true` if the uninstall succeeded
     */
    open fun uninstallApk(packageName: String, userId: Int): Boolean {
        val result = shell.execute("pm uninstall --user $userId \"$packageName\"", asRoot = true)
        return result.success
    }

    /**
     * Lists all packages installed inside the container identified by [userId].
     *
     * Runs `pm list packages --user <userId>` with root and returns the package
     * names found in the output (one per line in the form `"package:<name>"`).
     *
     * @param userId the Android user-ID of the target container
     * @return sorted list of package names, or an empty list on error
     */
    open fun listInstalledApps(userId: Int): List<String> {
        val result = shell.execute("pm list packages --user $userId", asRoot = true)
        if (!result.success) return emptyList()
        return result.stdout.lines()
            .mapNotNull { line ->
                if (line.startsWith("package:")) line.removePrefix("package:").trim() else null
            }
            .sorted()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Matches `"Success: created user id 11"` and captures the numeric ID. */
        internal val USER_ID_REGEX = Regex("""Success: created user id (\d+)""")

        /** Matches `"UserInfo{11:MyName:0}"` and captures the numeric user-ID. */
        internal val LIST_USER_ID_REGEX = Regex("""UserInfo\{(\d+):""")
    }
}
