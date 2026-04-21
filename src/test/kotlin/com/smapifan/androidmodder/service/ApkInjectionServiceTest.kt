package com.smapifan.androidmodder.service

import com.smapifan.androidmodder.model.ApkInjectionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ApkInjectionService].
 *
 * All shell commands are intercepted by a fake [ShellExecutor], so no real
 * apktool, apksigner, or Android device is needed.
 */
class ApkInjectionServiceTest {

    private class FakeShell(
        private val exitCode: Int = 0
    ) : ShellExecutor() {
        val commands = mutableListOf<String>()

        override fun execute(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return ShellResult(exitCode, "", "")
        }
    }

    private fun makeConfig(pkg: String = "com.example.game") = ApkInjectionConfig(
        packageName       = pkg,
        originalApkPath   = "/sdcard/backup/original.apk",
        keystorePath      = "/sdcard/my.keystore",
        keystorePassword  = "secret",
        keyAlias          = "mykey",
        apktoolPath       = "apktool",
        apksignerPath     = "apksigner",
        workDir           = "/tmp/apkpatch-$pkg"
    )

    // ── restoreOriginalApk ────────────────────────────────────────────────────

    @Test
    fun `restoreOriginalApk backs up saves before uninstalling`() {
        val fake = FakeShell(exitCode = 0)
        val svc  = ApkInjectionService(fake, SaveBackupService(fake))

        svc.restoreOriginalApk(makeConfig())

        // Backup (run-as cp) must appear BEFORE pm uninstall
        val commands = fake.commands
        val backupIdx    = commands.indexOfFirst { it.contains("run-as") && it.contains("cp -r") }
        val uninstallIdx = commands.indexOfFirst { it.contains("pm uninstall") }
        assertTrue(backupIdx >= 0,    "Expected run-as cp backup command; got: $commands")
        assertTrue(uninstallIdx >= 0, "Expected pm uninstall command; got: $commands")
        assertTrue(backupIdx < uninstallIdx, "Backup must happen BEFORE uninstall")
    }

    @Test
    fun `restoreOriginalApk writes restore script before uninstalling`() {
        val fake = FakeShell(exitCode = 0)
        val svc  = ApkInjectionService(fake, SaveBackupService(fake))

        svc.restoreOriginalApk(makeConfig())

        val commands     = fake.commands
        val scriptIdx    = commands.indexOfFirst { it.contains("restore_saves.sh") }
        val uninstallIdx = commands.indexOfFirst { it.contains("pm uninstall") }
        assertTrue(scriptIdx >= 0,
            "Expected restore_saves.sh write command; got: $commands")
        assertTrue(scriptIdx < uninstallIdx,
            "Restore script must be written BEFORE uninstall")
    }

    @Test
    fun `restoreOriginalApk installs original APK after uninstall`() {
        val fake = FakeShell(exitCode = 0)
        val svc  = ApkInjectionService(fake, SaveBackupService(fake))

        svc.restoreOriginalApk(makeConfig())

        val commands     = fake.commands
        val uninstallIdx = commands.indexOfFirst { it.contains("pm uninstall") }
        val installIdx   = commands.indexOfFirst {
            it.contains("pm install") && !it.contains("pm uninstall")
        }
        assertTrue(installIdx >= 0, "Expected pm install command; got: $commands")
        assertTrue(uninstallIdx < installIdx, "Uninstall must come BEFORE install")
    }

    @Test
    fun `restoreOriginalApk installs the originalApkPath from config`() {
        val fake = FakeShell(exitCode = 0)
        val svc  = ApkInjectionService(fake, SaveBackupService(fake))
        val cfg  = makeConfig()

        svc.restoreOriginalApk(cfg)

        assertTrue(
            fake.commands.any { it.contains("pm install") && it.contains(cfg.originalApkPath) },
            "pm install must reference originalApkPath=${cfg.originalApkPath}; got: ${fake.commands}"
        )
    }

    @Test
    fun `restoreOriginalApk returns false when backup fails`() {
        val fake = FakeShell(exitCode = 1) // all commands fail
        val svc  = ApkInjectionService(fake, SaveBackupService(fake))

        assertFalse(svc.restoreOriginalApk(makeConfig()))
        // Should not reach pm uninstall
        assertFalse(fake.commands.any { it.contains("pm uninstall") })
    }

    // ── manifest helpers ──────────────────────────────────────────────────────

    @Test
    fun `parseApplicationClass extracts existing application class`() {
        val svc = ApkInjectionService()
        val xml = """<application android:name="com.example.MyApp" android:label="App">"""
        assertEquals("com/example/MyApp", svc.parseApplicationClass(xml))
    }

    @Test
    fun `parseApplicationClass strips leading dot`() {
        val svc = ApkInjectionService()
        val xml = """<application android:name=".MyApp">"""
        assertEquals("MyApp", svc.parseApplicationClass(xml))
    }

    @Test
    fun `parseApplicationClass returns null when no name attribute exists`() {
        val svc = ApkInjectionService()
        val xml = """<application android:label="App">"""
        assertTrue(svc.parseApplicationClass(xml) == null)
    }

    @Test
    fun `patchManifestApplicationName replaces existing name`() {
        val svc = ApkInjectionService()
        val xml = """<application android:name="com.old.App" android:label="x">"""
        val out = svc.patchManifestApplicationName(xml, "com.new.Hook")
        assertTrue(out.contains("""android:name="com.new.Hook""""), "Name not replaced; got: $out")
        assertFalse(out.contains("com.old.App"))
    }

    @Test
    fun `patchManifestApplicationName inserts name when absent`() {
        val svc = ApkInjectionService()
        val xml = """<application android:label="x">"""
        val out = svc.patchManifestApplicationName(xml, "com.androidmodder.ModLoaderHook")
        assertTrue(out.contains("""android:name="com.androidmodder.ModLoaderHook""""))
    }

    @Test
    fun `enableDebuggable sets true when absent`() {
        val svc = ApkInjectionService()
        val xml = """<application android:label="x">"""
        val out = svc.enableDebuggable(xml)
        assertTrue(out.contains("""android:debuggable="true""""), "debuggable must be set; got: $out")
    }

    @Test
    fun `enableDebuggable replaces false with true`() {
        val svc = ApkInjectionService()
        val xml = """<application android:debuggable="false">"""
        val out = svc.enableDebuggable(xml)
        assertTrue(out.contains("""android:debuggable="true""""))
        assertFalse(out.contains("""android:debuggable="false""""))
    }

    @Test
    fun `enableDebuggable is idempotent when already true`() {
        val svc = ApkInjectionService()
        val xml = """<application android:debuggable="true">"""
        assertEquals(xml, svc.enableDebuggable(xml))
    }

    // ── smali generation ──────────────────────────────────────────────────────

    @Test
    fun `buildSmali contains hook class descriptor`() {
        val svc   = ApkInjectionService()
        val smali = svc.buildSmali("com.example.game", null)
        assertTrue(smali.contains(ApkInjectionService.HOOK_CLASS_DESCRIPTOR))
    }

    @Test
    fun `buildSmali uses default Application as super when null`() {
        val svc   = ApkInjectionService()
        val smali = svc.buildSmali("com.example.game", null)
        assertTrue(smali.contains("android/app/Application"), "Default super must be used; got:\n$smali")
    }

    @Test
    fun `buildSmali uses provided super class`() {
        val svc   = ApkInjectionService()
        val smali = svc.buildSmali("com.example.game", "com/example/BaseApp")
        assertTrue(smali.contains("com/example/BaseApp"), "Custom super must appear; got:\n$smali")
    }

    @Test
    fun `buildSmali references package name in smali body`() {
        val svc   = ApkInjectionService()
        val smali = svc.buildSmali("com.example.game", null)
        assertTrue(smali.contains("com.example.game"), "Package name must appear in smali; got:\n$smali")
    }

    @Test
    fun `buildSmali contains restoreSavesIfNeeded and applyPendingMods methods`() {
        val svc   = ApkInjectionService()
        val smali = svc.buildSmali("com.example.game", null)
        assertTrue(smali.contains("restoreSavesIfNeeded"), "Must contain restoreSavesIfNeeded method")
        assertTrue(smali.contains("applyPendingMods"),     "Must contain applyPendingMods method")
    }

    @Test
    fun `buildSmali restore method references restore_saves sh`() {
        val svc   = ApkInjectionService()
        val smali = svc.buildSmali("com.example.game", null)
        assertTrue(
            smali.contains("restore_saves.sh"),
            "smali must exec restore_saves.sh; got:\n$smali"
        )
    }

    @Test
    fun `buildSmali applyPendingMods references mod_launcher sh`() {
        val svc   = ApkInjectionService()
        val smali = svc.buildSmali("com.example.game", null)
        assertTrue(
            smali.contains("mod_launcher.sh"),
            "smali must exec mod_launcher.sh; got:\n$smali"
        )
    }

    // ── install / uninstall helpers ───────────────────────────────────────────

    @Test
    fun `installPatched uses pm install -r`() {
        val fake = FakeShell()
        ApkInjectionService(fake).installPatched("/sdcard/patched.apk")
        assertTrue(fake.commands.any { it.contains("pm install -r") && it.contains("patched.apk") })
    }

    @Test
    fun `installOriginal uses pm install without -r`() {
        val fake = FakeShell()
        ApkInjectionService(fake).installOriginal("/sdcard/original.apk")
        val cmd = fake.commands.first { it.contains("pm install") }
        assertTrue(cmd.contains("original.apk"))
        assertFalse(cmd.contains("-r"), "installOriginal must not use -r flag; got: $cmd")
    }

    @Test
    fun `uninstall issues pm uninstall with package name`() {
        val fake = FakeShell()
        ApkInjectionService(fake).uninstall("com.example.game")
        assertTrue(fake.commands.any { it.contains("pm uninstall") && it.contains("com.example.game") })
    }
}
