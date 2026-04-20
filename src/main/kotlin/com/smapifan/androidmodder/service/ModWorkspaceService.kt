package com.smapifan.androidmodder.service

import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class ModWorkspaceService {
    fun ensureWorkspace(root: Path): Path {
        root.createDirectories()
        return root
    }

    fun appWorkspace(root: Path, appName: String): Path {
        val appDir = root.resolve(appName)
        appDir.createDirectories()
        return appDir
    }

    /**
     * Exports all app data from the standard Android **internal** data directories into the workspace.
     *
     * ⚠️ **Root required** – `/data/data/` and `/data/` are protected by Android's sandbox.
     * This method only works on a rooted device.
     *
     * Copies:
     * - `<deviceDataRoot>/data/<appName>/`  → `<workspace>/<appName>/internal/data/data/<appName>/`
     * - `<deviceDataRoot>/<appName>/`       → `<workspace>/<appName>/internal/data/<appName>/`
     *
     * @param root           the workspace root directory
     * @param deviceDataRoot the device's `/data` directory (e.g. `Path.of("/data")`)
     * @param appName        the app / package name (e.g. `"com.gram.mergedragons"`)
     * @return the app workspace directory (`<root>/<appName>/`)
     */
    fun exportAppData(root: Path, deviceDataRoot: Path, appName: String): Path {
        val dest = appWorkspace(root, appName)

        // /data/data/<appName>/ → <workspace>/<appName>/internal/data/data/<appName>/
        val primarySource = deviceDataRoot.resolve("data").resolve(appName)
        if (Files.isDirectory(primarySource)) {
            copyTree(primarySource, dest.resolve("internal").resolve("data").resolve("data").resolve(appName))
        }

        // /data/<appName>/ → <workspace>/<appName>/internal/data/<appName>/
        val secondarySource = deviceDataRoot.resolve(appName)
        if (Files.isDirectory(secondarySource)) {
            copyTree(secondarySource, dest.resolve("internal").resolve("data").resolve(appName))
        }

        return dest
    }

    /**
     * Exports app data from the Android **external** storage into the workspace.
     *
     * ✅ **No root required** – `/sdcard/Android/data/<appName>/` is accessible to all apps.
     *
     * Copies:
     * - `<externalStorageRoot>/Android/data/<appName>/` → `<workspace>/<appName>/external/<appName>/`
     *
     * @param root                the workspace root directory
     * @param externalStorageRoot the device's external storage root (e.g. `Path.of("/sdcard")`)
     * @param appName             the app / package name (e.g. `"com.gram.mergedragons"`)
     * @return the app workspace directory (`<root>/<appName>/`)
     */
    fun exportExternalData(root: Path, externalStorageRoot: Path, appName: String): Path {
        val dest   = appWorkspace(root, appName)
        val source = externalStorageRoot.resolve("Android").resolve("data").resolve(appName)
        if (Files.isDirectory(source)) {
            copyTree(source, dest.resolve("external").resolve(appName))
        }
        return dest
    }

    /**
     * Imports previously exported **internal** app data back to the device data directories.
     *
     * ⚠️ **Root required** – reverses [exportAppData].
     *
     * @param root           the workspace root directory
     * @param deviceDataRoot the device's `/data` directory (e.g. `Path.of("/data")`)
     * @param appName        the app / package name
     */
    fun importAppData(root: Path, deviceDataRoot: Path, appName: String) {
        val src = appWorkspace(root, appName)

        // <workspace>/<appName>/internal/data/data/<appName>/ → /data/data/<appName>/
        val primaryWorkspace = src.resolve("internal").resolve("data").resolve("data").resolve(appName)
        if (Files.isDirectory(primaryWorkspace)) {
            copyTree(primaryWorkspace, deviceDataRoot.resolve("data").resolve(appName))
        }

        // <workspace>/<appName>/internal/data/<appName>/ → /data/<appName>/
        val secondaryWorkspace = src.resolve("internal").resolve("data").resolve(appName)
        if (Files.isDirectory(secondaryWorkspace)) {
            copyTree(secondaryWorkspace, deviceDataRoot.resolve(appName))
        }
    }

    /**
     * Imports previously exported **external** app data back to the device external storage.
     *
     * ✅ **No root required** – reverses [exportExternalData].
     *
     * @param root                the workspace root directory
     * @param externalStorageRoot the device's external storage root (e.g. `Path.of("/sdcard")`)
     * @param appName             the app / package name
     */
    fun importExternalData(root: Path, externalStorageRoot: Path, appName: String) {
        val workspace = appWorkspace(root, appName).resolve("external").resolve(appName)
        if (Files.isDirectory(workspace)) {
            copyTree(workspace, externalStorageRoot.resolve("Android").resolve("data").resolve(appName))
        }
    }

    fun listExtensions(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.list(root).use { paths ->
            return paths
                .filter { it.isRegularFile() && it.extension == "extension" }
                .sorted(compareBy { it.name.lowercase() })
                .toList()
        }
    }

    /**
     * Discovers user-provided mod files (`*.mod`) from [root].
     *
     * The app is a shell – it ships no mods itself. Users create and place
     * their `.mod` files directly in the workspace directory they chose at
     * startup. This method lists whatever the user has put there.
     */
    fun listMods(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.list(root).use { paths ->
            return paths
                .filter { it.isRegularFile() && it.extension == "mod" }
                .sorted(compareBy { it.name.lowercase() })
                .toList()
        }
    }

    fun unpackApk(apkPath: Path, destinationRoot: Path): Path {
        require(apkPath.extension.lowercase() == "apk") {
            "Only .apk files are supported, but received extension: ${apkPath.extension.ifBlank { "<none>" }}"
        }

        val targetDir = destinationRoot.resolve(apkPath.fileName.toString().removeSuffix(".apk"))
        targetDir.createDirectories()

        ZipInputStream(BufferedInputStream(Files.newInputStream(apkPath))).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val resolved = secureResolve(targetDir, entry.name)
                if (entry.isDirectory) {
                    resolved.createDirectories()
                } else {
                    resolved.parent?.createDirectories()
                    Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return targetDir
    }

    // --- internal helpers -------------------------------------------------

    internal fun copyTree(source: Path, destination: Path) {
        destination.createDirectories()
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dest = destination.resolve(source.relativize(src))
                if (Files.isDirectory(src)) {
                    dest.createDirectories()
                } else {
                    dest.parent?.createDirectories()
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun secureResolve(root: Path, entryName: String): Path {
        val resolved = root.resolve(entryName).normalize()
        if (!resolved.startsWith(root)) {
            throw IOException("Invalid zip entry outside destination: $entryName")
        }
        return resolved
    }
}
