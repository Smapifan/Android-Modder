package com.smapifan.androidmodder.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Android-Modder"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Launcher ist bereit."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 48)
        }

        val modsButton = Button(this).apply {
            text = "Mods öffnen"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Mods werden vorbereitet …", Toast.LENGTH_SHORT).show()
            }
        }

        val toolsButton = Button(this).apply {
            text = "Tools öffnen"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Tools werden vorbereitet …", Toast.LENGTH_SHORT).show()
            }
        }

        val aboutButton = Button(this).apply {
            text = "Info"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Android-Modder läuft.", Toast.LENGTH_SHORT).show()
            }
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(modsButton)
        container.addView(toolsButton)
        container.addView(aboutButton)

        setContentView(container)
    }
}
