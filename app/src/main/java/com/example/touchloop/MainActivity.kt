package com.example.touchloop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
        }
        val title = TextView(this).apply {
            text = "Touch Loop — ماکرو اندروید"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = "\nاین نسخه آزمایشی کلیک‌های قابل‌دسترسی و رویدادهای اسکرول را ثبت می‌کند.\n\n۱) سرویس دسترس‌پذیری را فعال کن.\n۲) به برنامه موردنظر برو.\n۳) از پنل شناور Record را بزن، عملیات را انجام بده و Stop را بزن.\n۴) Play را بزن تا ماکرو تا زمان Stop تکرار شود.\n\nورود متن، کشیدن دقیق انگشت و صفحات امن در این نسخه تضمین نشده‌اند."
            textSize = 16f
        }
        val settings = Button(this).apply {
            text = "فعال‌سازی Accessibility"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val clear = Button(this).apply {
            text = "پاک‌کردن ماکروی ذخیره‌شده"
            setOnClickListener {
                getSharedPreferences("touch_loop", MODE_PRIVATE).edit().remove("macro").apply()
                Toast.makeText(this@MainActivity, "ماکرو پاک شد", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(title)
        layout.addView(info)
        layout.addView(settings)
        layout.addView(clear)
        setContentView(layout)
    }
}
