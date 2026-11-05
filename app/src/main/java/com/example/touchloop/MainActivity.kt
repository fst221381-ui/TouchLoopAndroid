
package com.example.touchloop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var codeInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
        }

        val title = TextView(this).apply {
            text = "Touch Loop"
            textSize = 25f
            gravity = Gravity.CENTER
        }

        codeInput = EditText(this).apply {
            hint = "کد فعال‌سازی را وارد کن"
            singleLine = true
        }

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            text = if (prefs.getBoolean("license_active", false)) {
                "وضعیت ذخیره‌شده: فعال‌سازی قبلی"
            } else {
                "برای استفاده، کد فعال‌سازی لازم است."
            }
        }

        val activate = Button(this).apply {
            text = "فعال‌سازی"
            setOnClickListener {
                val code = codeInput.text.toString().trim()

                if (code.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "کد فعال‌سازی را وارد کن",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                status.text =
                    "اتصال به سرور هنوز پیاده‌سازی نشده است. کد فعال نشده."

                Toast.makeText(
                    this@MainActivity,
                    "فعال‌سازی آنلاین در مرحله بعد متصل می‌شود.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val accessibility = Button(this).apply {
            text = "تنظیمات Accessibility"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        val clear = Button(this).apply {
            text = "پاک‌کردن ماکروی ذخیره‌شده"
            setOnClickListener {
                prefs.edit().remove("macro").apply()
                Toast.makeText(
                    this@MainActivity,
                    "ماکرو پاک شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(codeInput)
        layout.addView(activate)
        layout.addView(accessibility)
        layout.addView(clear)

        setContentView(layout)
    }
}
