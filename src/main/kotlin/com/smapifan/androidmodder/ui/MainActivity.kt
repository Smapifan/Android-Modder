package com.smapifan.androidmodder.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "Android-Modder\nist aktiv."
            textSize = 18f
            setPadding(48, 96, 48, 48)
        }
        setContentView(text)
    }
}
