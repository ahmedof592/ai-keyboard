package com.example.aikeyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val prefs = getSharedPreferences("ai_kb_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "إعداد لوحة المفاتيح الذكية (AI Keyboard)"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }

        val apiKeyInput = EditText(this).apply {
            hint = "ألصق مفتاح Gemini API هنا..."
            setText(prefs.getString("api_key", ""))
        }

        val btnSaveKey = Button(this).apply {
            text = "1. حفظ مفتاح API"
            setOnClickListener {
                prefs.edit().putString("api_key", apiKeyInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "تم حفظ المفتاح بنجاح!", Toast.LENGTH_SHORT).show()
            }
        }

        val btnEnableKb = Button(this).apply {
            text = "2. تفعيل AI Smart Keyboard من إعدادات الهاتف"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }

        layout.addView(title)
        layout.addView(apiKeyInput)
        layout.addView(btnSaveKey)
        layout.addView(btnEnableKb)

        setContentView(layout)
    }
}
