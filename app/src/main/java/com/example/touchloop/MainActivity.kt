
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var codeInput: EditText

    private val endpoint =
        "https://rkllnarnvbylpphshweu.supabase.co/functions/v1/activate-code"

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
            setOnClickListener { activateCode() }
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

    private fun activateCode() {
        val code = codeInput.text.toString().trim()

        if (code.length < 8) {
            codeInput.error = "کد معتبر را وارد کن"
            return
        }

        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)
        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: ""

        if (deviceId.length < 8) {
            status.text = "شناسه گوشی قابل دریافت نیست."
            return
        }

        status.text = "در حال بررسی کد با سرور..."
        codeInput.isEnabled = false

        Thread {
            var connection: HttpURLConnection? = null

            try {
                val apiKey = getString(R.string.supabase_publishable_key)

                connection = (URL(endpoint).openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                    setRequestProperty("apikey", apiKey)
                }

                val request = JSONObject().apply {
                    put("code", code)
                    put("device_id", deviceId)
                }

                OutputStreamWriter(
                    connection.outputStream,
                    Charsets.UTF_8
                ).use {
                    it.write(request.toString())
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseText = stream?.bufferedReader()
                    ?.use { it.readText() } ?: "{}"

                val response = JSONObject(responseText)
                val success = response.optBoolean("success", false)

                runOnUiThread {
                    if (success && responseCode in 200..299) {
                        val expiresAt = response.optString("expires_at", "")
                        val remaining = response.optLong(
                            "remaining_seconds", 0L
                        )

                        if (expiresAt.isNotBlank() && remaining > 0L) {
                            prefs.edit()
                                .putBoolean("license_active", true)
                                .putString("license_device_id", deviceId)
                                .putString("license_expires_at", expiresAt)
                                .putLong("license_remaining_seconds", remaining)
                                .apply()

                            status.text =
                                "فعال‌سازی موفق شد.\nزمان باقی‌مانده: " +
                                "${remaining / 86400} روز"
                            codeInput.setText("")
                        } else {
                            status.text =
                                "پاسخ سرور ناقص است؛ فعال‌سازی تأیید نشد."
                        }
                    } else {
                        val message = response.optString(
                            "message", "کد تأیید نشد."
                        )
                        status.text = "خطا: $message"
                    }

                    codeInput.isEnabled = true
                }

            } catch (e: Exception) {
                runOnUiThread {
                    status.text =
                        "اتصال ناموفق بود. اینترنت و تنظیمات سرور را بررسی کن."
                    codeInput.isEnabled = true
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
