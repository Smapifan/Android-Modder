// Registry of bundled VM system apps and runtime-registered user-supplied APKs with category metadata.
// Registrierung gebündelter VM-System-Apps und zur Laufzeit registrierter benutzerdefinierter APKs mit Kategorie-Metadaten.

package com.smapifan.androidmodder.service

// ═════════════════════════════════════════════════════════════════════════════
//  SystemAppsRegistry – manifest of bundled VM system apps
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Manifest of "standard apps" that ship pre-installed in the virtual machine.
 *
 * Each entry describes one system APK that is bundled under
 * `assets/system_apps/<assetFileName>` in the host APK.  On first boot,
 * [VmBootService] reads this registry and installs every [autoInstall] entry
 * into the virtual sandbox managed by [AppInstallManagerService].
 *
 * ## APK bundling conventions
 *
 * APKs are placed in the Android asset folder:
 * ```
 * app/src/main/assets/system_apps/
 *   rootbrowser.apk          ← Root Browser (virtual FS navigation)
 *   rameditor.apk            ← RAM Editor  (multi-type memory scanner)
 *   microg_gmscore.apk       ← MicroG GmsCore (open-source GApps replacement)
 *   microg_companion.apk     ← MicroG Companion (open-source Play compatibility)
 *   fdroid.apk               ← F-Droid (open-source app store)
 *   newpipe.apk              ← NewPipe (open-source YouTube client)
 * ```
 *
 * ## Copyright note
 *
 * Proprietary Google APKs (Google Play Services, Play Store, YouTube, etc.)
 * **cannot** be bundled here — they are copyrighted, proprietary software.
 * Users who require GApps must supply those APKs themselves; they can be
 * imported via [AppInstallManagerService.installApkFile] and registered as
 * [USER_SUPPLIED] entries through [SystemAppsRegistry.userSupplied].
 *
 * @param packageId     Android package name, e.g. `"com.smapifan.rootbrowser"`.
 * @param label         Human-readable name shown in the VM app drawer.
 * @param assetFileName File name inside `assets/system_apps/`, e.g. `"rootbrowser.apk"`.
 * @param category      Functional category for grouping in the UI.
 * @param autoInstall   When `true`, [VmBootService] installs this app on first boot.
 *                      Set to `false` for optional or large apps that the user
 *                      should opt-in to install on demand.
 */
data class SystemAppEntry(
    val packageId: String,
    val label: String,
    val assetFileName: String,
    val category: SystemAppCategory,
    val autoInstall: Boolean = true
)

/** Functional category for a [SystemAppEntry]. */
enum class SystemAppCategory {
    /** File browser / data explorer. */
    FILE_BROWSER,
    /** RAM / memory editor or analyser. */
    RAM_TOOL,
    /** Google Mobile Services compatibility layer (e.g. MicroG). */
    GMS_LAYER,
    /** App store or package manager. */
    APP_STORE,
    /** Media / streaming apps. */
    MEDIA,
    /** User-supplied APK registered at runtime (not bundled in assets). */
    USER_SUPPLIED
}

/**
 * Central registry of VM system apps.
 *
 * Access the [bundled] list for apps whose APKs ship inside `assets/system_apps/`.
 * Use [userSupplied] to register additional APKs that the user provides at runtime
 * (e.g. proprietary GApps, YouTube).
 *
 * ## Minimal-RAM design
 *
 * This object is a lightweight, allocation-free manifest.  It holds only
 * strings and enums — no file handles, no streams, no bitmaps.  [VmBootService]
 * reads it once during the one-time installation pass and then discards its
 * reference; the registry is never held in a long-lived field.
 */
object SystemAppsRegistry {

    // ─────────────────────────────────────────────────────────────────────────
    //  Bundled system apps (assets/system_apps/)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * All APKs that are expected to be present in `assets/system_apps/`.
     *
     * [VmBootService] iterates this list and installs each [SystemAppEntry.autoInstall]
     * entry that is not yet present in the virtual sandbox.
     */
    val bundled: List<SystemAppEntry> = listOf(

        // ── File browser ────────────────────────────────────────────────────
        SystemAppEntry(
            packageId     = "com.smapifan.rootbrowser",
            label         = "Root Browser",
            assetFileName = "rootbrowser.apk",
            category      = SystemAppCategory.FILE_BROWSER,
            autoInstall   = true
        ),

        // ── RAM tools ───────────────────────────────────────────────────────
        SystemAppEntry(
            packageId     = "com.smapifan.rameditor",
            label         = "RAM Editor",
            assetFileName = "rameditor.apk",
            category      = SystemAppCategory.RAM_TOOL,
            autoInstall   = true
        ),

        // ── GMS compatibility layer (open-source GApps replacement) ─────────
        // MicroG replaces Google Play Services without proprietary binaries.
        // See https://microg.org/
        SystemAppEntry(
            packageId     = "com.google.android.gms",   // MicroG installs under the real GMS package ID
            label         = "MicroG Services (GMS layer)",
            assetFileName = "microg_gmscore.apk",
            category      = SystemAppCategory.GMS_LAYER,
            autoInstall   = true
        ),
        SystemAppEntry(
            packageId     = "com.google.android.gsf",
            label         = "MicroG GSF Proxy",
            assetFileName = "microg_companion.apk",
            category      = SystemAppCategory.GMS_LAYER,
            autoInstall   = true
        ),

        // ── Open-source app store ───────────────────────────────────────────
        SystemAppEntry(
            packageId     = "org.fdroid.fdroid",
            label         = "F-Droid",
            assetFileName = "fdroid.apk",
            category      = SystemAppCategory.APP_STORE,
            autoInstall   = true
        ),

        // ── Media / streaming ───────────────────────────────────────────────
        // NewPipe is a libre YouTube client.  See https://newpipe.net/
        // (YouTube / Google apps cannot be bundled — copyright; users
        //  may supply them via installUserApk / USER_SUPPLIED entries.)
        SystemAppEntry(
            packageId     = "org.schabi.newpipe",
            label         = "NewPipe (YouTube client)",
            assetFileName = "newpipe.apk",
            category      = SystemAppCategory.MEDIA,
            autoInstall   = true
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Runtime-registered user-supplied APKs
    // ─────────────────────────────────────────────────────────────────────────

    private val _userSupplied = mutableListOf<SystemAppEntry>()

    /**
     * APKs registered by the user at runtime (e.g. proprietary GApps, YouTube).
     *
     * These are **not** bundled in `assets/system_apps/`; the user supplies the
     * APK file and calls [registerUserApk] to add it here.  [VmBootService]
     * ignores this list during automated boot — user-supplied apps are installed
     * on demand via [VmBootService.installUserApk].
     */
    val userSupplied: List<SystemAppEntry> get() = _userSupplied.toList()

    /**
     * Registers a user-supplied APK so it appears in the unified app list.
     *
     * This does **not** install the APK into the virtual sandbox; call
     * [VmBootService.installUserApk] for that.
     *
     * @param packageId     Android package name of the APK.
     * @param label         Human-readable name for the app drawer.
     * @param category      Functional category; defaults to [SystemAppCategory.USER_SUPPLIED].
     */
    fun registerUserApk(
        packageId: String,
        label: String,
        category: SystemAppCategory = SystemAppCategory.USER_SUPPLIED
    ) {
        _userSupplied.removeAll { it.packageId == packageId }
        _userSupplied += SystemAppEntry(
            packageId     = packageId,
            label         = label,
            assetFileName = "",          // no asset — APK provided by the user
            category      = category,
            autoInstall   = false
        )
    }

    /**
     * Returns all entries — [bundled] + [userSupplied] — sorted by
     * [SystemAppEntry.label].
     */
    fun all(): List<SystemAppEntry> =
        (bundled + userSupplied).sortedBy { it.label }
}
