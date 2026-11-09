package com.kainoa.doomstopper

// DO NOT DELETE YOUR PACKAGE LINE AT THE TOP! Keep it here.

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a dark, calming background
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // Title
        val title = TextView(this).apply {
            text = "DoomStopper"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        // Button 1: Overlay Permission
        val btnOverlay = Button(this).apply {
            text = "1. Enable Screen Overlay"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        // Button 2: Accessibility Permission
        val btnAccessibility = Button(this).apply {
            text = "2. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(title)
        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)

        setContentView(layout)
    }
}