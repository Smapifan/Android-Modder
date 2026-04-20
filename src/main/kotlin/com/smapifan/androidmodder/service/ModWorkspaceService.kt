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

    fun exportSave(root: Path, appName: String, sourceSave: Path): Path {
        val destination = appWorkspace(root, appName).resolve(sourceSave.fileName.toString())
        Files.copy(sourceSave, destination, StandardCopyOption.REPLACE_EXISTING)
        return destination
    }

    fun importSave(root: Path, appName: String, saveName: String, targetSavePath: Path): Path {
        val source = appWorkspace(root, appName).resolve(saveName)
        require(source.isRegularFile()) { "Save file not found in workspace: $source" }
        targetSavePath.parent?.createDirectories()
        Files.copy(source, targetSavePath, StandardCopyOption.REPLACE_EXISTING)
        return targetSavePath
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

    private fun secureResolve(root: Path, entryName: String): Path {
        val resolved = root.resolve(entryName).normalize()
        if (!resolved.startsWith(root)) {
            throw IOException("Invalid zip entry outside destination: $entryName")
        }
        return resolved
    }
}
