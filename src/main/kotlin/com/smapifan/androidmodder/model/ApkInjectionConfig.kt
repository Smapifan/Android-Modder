package com.smapifan.androidmodder.model

/**
 * Configuration for the one-time APK patching pipeline.
 *
 * [com.smapifan.androidmodder.service.ApkInjectionService] uses these
 * parameters to:
 *
 * 1. Decompile the original APK with `apktool`.
 * 2. Inject the mod-loader smali bootstrap.
 * 3. Set `android:debuggable="true"` so that `run-as <pkg>` works for
 *    save backup later.
 * 4. Rebuild, sign with [keystorePath], and reinstall via `pm install`.
 *
 * The [originalApkPath] **must** be stored somewhere persistent (e.g. on the
 * SD card) so that [com.smapifan.androidmodder.service.ApkInjectionService
 * .restoreOriginalApk] can reinstall the unmodified APK at any time.
 *
 * @param packageName      Android package name, e.g. `"com.gram.mergedragons"`.
 * @param originalApkPath  Full path to the unpatched APK (kept for restore).
 * @param keystorePath     JKS / BKS keystore used for APK re-signing.
 * @param keystorePassword Keystore password.
 * @param keyAlias         Key alias inside the keystore.
 * @param keyPassword      Key-entry password (defaults to [keystorePassword]).
 * @param apktoolPath      `apktool` executable name or full path (default: `"apktool"`).
 * @param apksignerPath    `apksigner` from Android SDK build-tools (default: `"apksigner"`).
 * @param workDir          Scratch directory for decompile / rebuild artefacts;
 *                         created automatically if absent.
 */
data class ApkInjectionConfig(
    val packageName: String,
    val originalApkPath: String,
    val keystorePath: String,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String = keystorePassword,
    val apktoolPath: String = "apktool",
    val apksignerPath: String = "apksigner",
    val workDir: String = "/tmp/androidmodder-apkpatch-$packageName"
)
