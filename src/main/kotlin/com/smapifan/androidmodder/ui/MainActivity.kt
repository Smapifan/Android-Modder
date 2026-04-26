package com.smapifan.androidmodder.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var selectedApkUri: Uri? = null
    private lateinit var selectedApkText: TextView
    private lateinit var appListView: ListView
    private lateinit var appAdapter: ArrayAdapter<AppItem>

    companion object {
        private const val REQUEST_PICK_APK = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Android-Modder"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "APK installieren, Apps anzeigen und Root-Ordner prüfen."
            textSize = 16f
            gravity = Gravity.START
            setPadding(0, 24, 0, 48)
        }

        selectedApkText = TextView(this).apply {
            text = "Keine APK ausgewählt."
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }

        val pickApkButton = Button(this).apply {
            text = "APK auswählen"
            setOnClickListener {
                pickApkFile()
            }
        }

        val installApkButton = Button(this).apply {
            text = "APK installieren"
            setOnClickListener {
                installSelectedApk()
            }
        }

        val loadAppsButton = Button(this).apply {
            text = "Apps + Icons laden"
            setOnClickListener {
                loadInstalledApps()
            }
        }

        val rootBrowserButton = Button(this).apply {
            text = "Root Browser (/data/data)"
            setOnClickListener {
                openRootBrowserPreview()
            }
        }

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
                        textSize = 15f
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
            dividerHeight = 12
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(selectedApkText)
        container.addView(pickApkButton)
        container.addView(installApkButton)
        container.addView(loadAppsButton)
        container.addView(rootBrowserButton)
        container.addView(appListView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            900
        ))

        scroll.addView(container)
        setContentView(scroll)
    }

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        startActivityForResult(intent, REQUEST_PICK_APK)
    }

    private fun installSelectedApk() {
        val uri = selectedApkUri
        if (uri == null) {
            Toast.makeText(this, "Bitte zuerst APK auswählen.", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "${entries.size} Apps geladen.", Toast.LENGTH_SHORT).show()
    }

    private fun openRootBrowserPreview() {
        val command = listOf("su", "-c", "ls -la /data/data | head -n 60")
        val output = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        }.getOrElse {
            "Root-Zugriff fehlgeschlagen: ${it.message}\n\nPrüfe, ob das Gerät gerootet ist und SU-Rechte vergeben wurden."
        }

        AlertDialog.Builder(this)
            .setTitle("Root Browser Vorschau")
            .setMessage(output.ifBlank { "Keine Ausgabe." })
            .setPositiveButton("OK", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedApkUri = uri
        val name = queryDisplayName(uri) ?: uri.toString()
        selectedApkText.text = "Ausgewählt: $name"
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    private data class AppItem(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable
    ) {
        override fun toString(): String = label
    }
}
