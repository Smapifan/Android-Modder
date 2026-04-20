package com.smapifan.androidmodder.service

import java.nio.file.Path

/**
 * Configuration for launching a game through Android-Modder's launcher.
 *
 * @param packageName      the game's package name, e.g. `"com.gram.mergedragons"`
 * @param launchCommand    shell command to start the game,
 *                         e.g. `"am start -n com.gram.mergedragons/.MainActivity"`
 * @param useRootForData   if `true`, data export/import uses `su` (root) to access
 *                         `/data/data/<packageName>/`; if `false`, only external
 *                         storage (`/sdcard/Android/data/<packageName>/`) is used
 * @param deviceDataRoot   root of the device's internal data directory, default `/data`
 * @param externalStorageRoot root of external storage, default `/sdcard`
 * @param importAfterExit  if `true`, modified workspace data is imported back after
 *                         the game process exits
 */
data class GameLaunchConfig(
    val packageName: String,
    val launchCommand: String,
    val useRootForData: Boolean = false,
    val deviceDataRoot: String = "/data",
    val externalStorageRoot: String = "/sdcard",
    val importAfterExit: Boolean = true
)
