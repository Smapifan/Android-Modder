package com.smapifan.androidmodder.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TabHost
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.smapifan.androidmodder.service.AppInstallManagerService
import com.smapifan.androidmodder.service.I18nService
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ── APK picker state ──────────────────────────────────────────────────────
    private var selectedApkUri: Uri? = null
    private lateinit var selectedApkText: TextView

    // ── Device-apps list (tab 1) ──────────────────────────────────────────────
    private lateinit var appListView: ListView
    private lateinit var appAdapter: ArrayAdapter<AppItem>

    // ── Virtual-apps list (tab 2) ─────────────────────────────────────────────
    private lateinit var virtualListView: ListView
    private lateinit var virtualAdapter: ArrayAdapter<String>

    // ── Services ──────────────────────────────────────────────────────────────
    private lateinit var installManager: AppInstallManagerService
    private lateinit var i18n: I18nService

    companion object {
        private const val REQUEST_PICK_APK = 1001
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  onCreate
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installManager = AppInstallManagerService(this)
        i18n = I18nService(Locale.getDefault())

        // Root TabHost layout
        val tabHost = TabHost(this)
        tabHost.id = android.R.id.tabhost

        val tabWidget = android.widget.TabWidget(this).apply {
            id = android.R.id.tabs
        }
        val tabContent = android.widget.FrameLayout(this).apply {
            id = android.R.id.tabcontent
        }

        val tabRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabWidget)
            addView(tabContent)
        }
        tabHost.addView(tabRoot)
        tabHost.setup()

        // Tab 1 – Install APK
        tabHost.addTab(
            tabHost.newTabSpec("install")
                .setIndicator(i18n.get("install.tab.title"))
                .setContent { buildInstallTab() }
        )

        // Tab 2 – Virtual Apps (sandbox)
        tabHost.addTab(
            tabHost.newTabSpec("virtual")
                .setIndicator(i18n.get("virtual.tab.title"))
                .setContent { buildVirtualAppsTab() }
        )

        // Tab 3 – Device Apps
        tabHost.addTab(
            tabHost.newTabSpec("device")
                .setIndicator(i18n.get("device.tab.title"))
                .setContent { buildDeviceAppsTab() }
        )

        // Tab 4 – Root Browser
        tabHost.addTab(
            tabHost.newTabSpec("root")
                .setIndicator(i18n.get("root.tab.title"))
                .setContent { buildRootBrowserTab() }
        )

        setContentView(tabHost)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Tab builders
    // ─────────────────────────────────────────────────────────────────────────

    /** Tab 1 – Install APK into virtual sandbox. */
    private fun buildInstallTab(): View {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(sectionHeader(i18n.get("install.section.header")))

        layout.addView(infoText(i18n.get("install.description")))

        selectedApkText = TextView(this).apply {
            text = i18n.get("install.no_apk_selected")
            textSize = 14f
            setPadding(0, 24, 0, 8)
        }
        layout.addView(selectedApkText)

        layout.addView(button(i18n.get("install.pick_apk")) { pickApkFile() })
        layout.addView(button(i18n.get("install.install_virtual")) { installApkVirtual() })
        layout.addView(button(i18n.get("install.install_system")) { installApkSystem() })

        layout.addView(divider())
        layout.addView(sectionHeader(i18n.get("install.paths.header")))
        layout.addView(infoText(
            i18n.format("install.paths.description", filesDir.absolutePath)
        ))

        scroll.addView(layout)
        return scroll
    }

    /** Tab 2 – Browse and manage virtual (sandboxed) apps. */
    private fun buildVirtualAppsTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(sectionHeader(i18n.get("virtual.section.header")))
        layout.addView(infoText(i18n.get("virtual.description")))

        layout.addView(button(i18n.get("virtual.refresh")) { refreshVirtualApps() })

        virtualAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        virtualListView = ListView(this).apply {
            adapter = virtualAdapter
            isNestedScrollingEnabled = true
            dividerHeight = 4
            setOnItemClickListener { _, _, pos, _ ->
                val pkg = virtualAdapter.getItem(pos) ?: return@setOnItemClickListener
                showVirtualAppDetails(pkg)
            }
        }
        layout.addView(virtualListView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        refreshVirtualApps()
        return layout
    }

    /** Tab 3 – List all apps installed on the device. */
    private fun buildDeviceAppsTab(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(sectionHeader(i18n.get("device.section.header")))

        layout.addView(button(i18n.get("device.load_apps")) { loadInstalledApps() })

        appAdapter = object : ArrayAdapter<AppItem>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12, 12, 12, 12)
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply {
                        id = View.generateViewId()
                        layoutParams = LinearLayout.LayoutParams(96, 96)
                    })
                    addView(TextView(context).apply {
                        id = View.generateViewId()
                        textSize = 14f
                        setPadding(24, 0, 0, 0)
                    })
                }
                val item = getItem(position)
                val iconView = row.getChildAt(0) as ImageView
                val labelView = row.getChildAt(1) as TextView
                if (item != null) {
                    iconView.setImageDrawable(item.icon)
                    labelView.text = "${item.label}\n${item.packageName}"
                }
                return row
            }
        }
        appListView = ListView(this).apply {
            adapter = appAdapter
            isNestedScrollingEnabled = true
            dividerHeight = 8
        }
        layout.addView(appListView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        return layout
    }

    /** Tab 4 – In-app file browser for the virtual data/data sandbox. */
    private fun buildRootBrowserTab(): View {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(sectionHeader(i18n.get("root.section.header")))
        layout.addView(infoText(i18n.get("root.description")))

        layout.addView(button(i18n.get("root.browse_sandbox")) {
            openRootBrowserPreview(filesDir.absolutePath)
        })

        scroll.addView(layout)
        return scroll
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_APK)
    }

    /** Install the selected APK into Android-Modder's virtual sandbox. */
    private fun installApkVirtual() {
        val uri = selectedApkUri ?: run {
            Toast.makeText(this, i18n.get("install.no_apk_selected"), Toast.LENGTH_SHORT).show()
            return
        }

        // Copy URI to a temp file so PackageManager can inspect it
        val tmpFile = File(cacheDir, "pending_install.apk")
        runCatching {
            contentResolver.openInputStream(uri)?.use { ins ->
                tmpFile.outputStream().use { ins.copyTo(it) }
            }
        }.onFailure {
            Toast.makeText(this, i18n.format("install.copy_failed", it.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }

        val packageId = installManager.installApkFile(tmpFile)
        tmpFile.delete()

        if (packageId != null) {
            Toast.makeText(this, i18n.format("install.success", packageId), Toast.LENGTH_LONG).show()
            refreshVirtualApps()
        } else {
            Toast.makeText(this, i18n.get("install.failed"), Toast.LENGTH_LONG).show()
        }
    }

    /** Open the system package installer for the selected APK. */
    private fun installApkSystem() {
        val uri = selectedApkUri ?: run {
            Toast.makeText(this, i18n.get("install.no_apk_selected"), Toast.LENGTH_SHORT).show()
            return
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    }

    private fun loadInstalledApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val entries = packageManager.queryIntentActivities(launcherIntent, 0)
            .map {
                AppItem(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(packageManager)
                )
            }
            .sortedBy { it.label.lowercase() }

        appAdapter.clear()
        appAdapter.addAll(entries)
        appAdapter.notifyDataSetChanged()
        Toast.makeText(this, i18n.format("device.apps_loaded", entries.size), Toast.LENGTH_SHORT).show()
    }

    private fun refreshVirtualApps() {
        val packages = installManager.listInstalledPackages()
        virtualAdapter.clear()
        if (packages.isEmpty()) {
            virtualAdapter.add(i18n.get("virtual.none_installed"))
        } else {
            virtualAdapter.addAll(packages)
        }
        virtualAdapter.notifyDataSetChanged()
    }

    /** Shows a dialog with the virtual data-directory structure for [packageId]. */
    private fun showVirtualAppDetails(packageId: String) {
        val label = installManager.appLabel(packageId)
        val dataRoot = installManager.dataDataRoot(packageId)
        val modsRoot = installManager.modsRoot(packageId)
        val mods = installManager.listModLayers(packageId)

        val sb = StringBuilder()
        sb.appendLine(i18n.format("virtual.detail.package", packageId))
        sb.appendLine(i18n.format("virtual.detail.label", label))
        sb.appendLine()
        sb.appendLine(i18n.format("virtual.detail.data_path", dataRoot))
        sb.appendLine()
        sb.appendLine(i18n.format("virtual.detail.mods_path", modsRoot))
        if (mods.isEmpty()) {
            sb.appendLine("  ${i18n.get("virtual.detail.no_mods")}")
        } else {
            mods.forEach { sb.appendLine("  • $it") }
        }

        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(sb.toString())
            .setPositiveButton(i18n.get("dialog.ok"), null)
            .setNeutralButton(i18n.get("virtual.detail.add_mod")) { _, _ -> promptAddModLayer(packageId) }
            .setNegativeButton(i18n.get("virtual.detail.uninstall")) { _, _ -> confirmUninstall(packageId) }
            .show()
    }

    private fun promptAddModLayer(packageId: String) {
        val input = android.widget.EditText(this).apply {
            hint = i18n.get("virtual.mod_name_hint")
        }
        AlertDialog.Builder(this)
            .setTitle(i18n.get("virtual.add_mod_title"))
            .setView(input)
            .setPositiveButton(i18n.get("dialog.ok")) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val ok = installManager.createModLayer(packageId, name)
                    val msg = if (ok) i18n.format("virtual.mod_created", name)
                              else i18n.get("virtual.mod_create_failed")
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(i18n.get("dialog.cancel"), null)
            .show()
    }

    private fun confirmUninstall(packageId: String) {
        AlertDialog.Builder(this)
            .setTitle(i18n.get("virtual.uninstall_title"))
            .setMessage(i18n.format("virtual.uninstall_confirm", packageId))
            .setPositiveButton(i18n.get("virtual.uninstall_confirm_btn")) { _, _ ->
                val ok = installManager.uninstall(packageId)
                val msg = if (ok) i18n.format("virtual.uninstalled", packageId)
                          else i18n.get("virtual.uninstall_failed")
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                refreshVirtualApps()
            }
            .setNegativeButton(i18n.get("dialog.cancel"), null)
            .show()
    }

    private fun openRootBrowserPreview(path: String) {
        val sb = StringBuilder()
        sb.appendLine(i18n.format("root.sandbox_listing", path))
        sb.appendLine()
        listDirRecursive(File(path), sb, depth = 0, maxDepth = 4)
        val output = sb.toString()

        AlertDialog.Builder(this)
            .setTitle(i18n.format("root.preview_title", path))
            .setMessage(output.ifBlank { i18n.get("root.no_output") })
            .setPositiveButton(i18n.get("dialog.ok"), null)
            .show()
    }

    private fun listDirRecursive(dir: File, sb: StringBuilder, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        for (f in files) {
            if (f.isDirectory) {
                sb.appendLine("$indent${f.name}/")
                listDirRecursive(f, sb, depth + 1, maxDepth)
            } else {
                sb.appendLine("$indent${f.name}  (${f.length()} B)")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Activity result
    // ─────────────────────────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedApkUri = uri
        val name = queryDisplayName(uri) ?: uri.toString()
        selectedApkText.text = i18n.format("install.selected", name)
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  View helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        gravity = Gravity.START
        setPadding(0, 24, 0, 8)
    }

    private fun infoText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, 0, 0, 16)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 8, 0, 0) }
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        ).also { it.setMargins(0, 16, 0, 16) }
        setBackgroundColor(0xFFCCCCCC.toInt())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Data classes
    // ─────────────────────────────────────────────────────────────────────────

    private data class AppItem(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable
    ) {
        override fun toString(): String = label
    }
}
