
package com.example.touchloop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val baseUrl =
        "https://rkllnarnvbylpphshweu.supabase.co"

    private val activateEndpoint =
        "$baseUrl/functions/v1/activate-code"

    private val adminEndpoint =
        "$baseUrl/functions/v1/admin-codes"

    private lateinit var status: TextView
    private lateinit var codeInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var adminStatus: TextView

    private var adminToken: String? = null
    private var generatedCode: String? = null

    private val prefs by lazy {
        getSharedPreferences("touch_loop", MODE_PRIVATE)
    }

    private val apiKey by lazy {
        getString(R.string.supabase_publishable_key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 24, 28, 24)
        }

        fun addTitle(text: String, size: Float = 20f) {
            layout.addView(TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.CENTER
                setPadding(0, 14, 0, 10)
            })
        }

        fun addButton(text: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            })
        }

        addTitle("Touch Loop", 26f)

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            text = if (prefs.getBoolean("license_active", false)) {
                "وضعیت ذخیره‌شده: فعال‌سازی قبلی"
            } else {
                "برای استفاده، کد فعال‌سازی لازم است."
            }
        }
        layout.addView(status)

        codeInput = EditText(this).apply {
            hint = "کد فعال‌سازی"
            setSingleLine(true)
        }
        layout.addView(codeInput)

        addButton("فعال‌سازی") { activateCode() }

        addButton("تنظیمات Accessibility") {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }

        addButton("پاک‌کردن ماکروی ذخیره‌شده") {
            prefs.edit().remove("macro").apply()
            Toast.makeText(
                this,
                "ماکرو پاک شد",
                Toast.LENGTH_SHORT
            ).show()
        }

        addTitle("پنل مدیریت کدها", 22f)

        emailInput = EditText(this).apply {
            hint = "ایمیل حساب مدیر"
            setSingleLine(true)
        }
        layout.addView(emailInput)

        passwordInput = EditText(this).apply {
            hint = "رمز عبور حساب مدیر"
            setSingleLine(true)
            inputType = 129
        }
        layout.addView(passwordInput)

        addButton("ورود مدیر") { adminLogin() }

        daysInput = EditText(this).apply {
            hint = "مدت اعتبار به روز، مثلاً 30"
            setSingleLine(true)
            inputType = 2
        }
        layout.addView(daysInput)

        addButton("ساخت کد فعال‌سازی") { createAdminCode() }

        adminStatus = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            text = "برای ساخت کد، ابتدا وارد حساب مدیر شو."
            setPadding(0, 12, 0, 12)
        }
        layout.addView(adminStatus)

        addButton("کپی کد ساخته‌شده") {
            val code = generatedCode
            if (code.isNullOrBlank()) {
                adminStatus.text = "هنوز کدی ساخته نشده است."
            } else {
                val clipboard = getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Activation code", code)
                )

                Toast.makeText(
                    this,
                    "کد کپی شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        setContentView(
            ScrollView(this).apply {
                addView(layout)
            }
        )
    }

    private fun adminLogin() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (email.isBlank() || password.isBlank()) {
            adminStatus.text = "ایمیل و رمز عبور را وارد کن."
            return
        }

        adminStatus.text = "در حال ورود مدیر..."

        Thread {
            var connection: HttpURLConnection? = null

            try {
                connection = (URL(
                    "$baseUrl/auth/v1/token?grant_type=password"
                ).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("apikey", apiKey)
                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                }

                val body = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }

                OutputStreamWriter(
                    connection.outputStream,
                    Charsets.UTF_8
                ).use { it.write(body.toString()) }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val text = stream?.bufferedReader()
                    ?.use { it.readText() } ?: "{}"

                val response = JSONObject(text)
                val token = response.optString("access_token", "")

                runOnUiThread {
                    if (responseCode in 200..299 &&
                        token.isNotBlank()
                    ) {
                        adminToken = token
                        passwordInput.text.clear()
                        adminStatus.text =
                            "ورود انجام شد. حالا مدت اعتبار را وارد کن."
                    } else {
                        adminToken = null
                        adminStatus.text =
                            "ورود ناموفق بود. ایمیل و رمز را بررسی کن."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    adminStatus.text =
                        "اتصال ناموفق بود؛ اینترنت و تنظیمات سرور را بررسی کن."
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun createAdminCode() {
        val token = adminToken

        if (token.isNullOrBlank()) {
            adminStatus.text = "ابتدا با حساب مدیر وارد شو."
            return
        }

        val days = daysInput.text.toString().trim().toIntOrNull()

        if (days == null || days !in 1..3650) {
            daysInput.error = "عدد بین 1 تا 3650 وارد کن"
            return
        }

        adminStatus.text = "در حال ساخت کد..."

        Thread {
            var connection: HttpURLConnection? = null

            try {
                connection = (URL(adminEndpoint).openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("apikey", apiKey)
                    setRequestProperty(
                        "Authorization",
                        "Bearer $token"
                    )
                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                }

                val body = JSONObject().apply {
                    put("duration_days", days)
                }

                OutputStreamWriter(
                    connection.outputStream,
                    Charsets.UTF_8
                ).use { it.write(body.toString()) }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val text = stream?.bufferedReader()
                    ?.use { it.readText() } ?: "{}"

                val response = JSONObject(text)
                val success = response.optBoolean("success", false)
                val code = response.optString("code", "")

                runOnUiThread {
                    if (responseCode in 200..299 &&
                        success && code.isNotBlank()
                    ) {
                        generatedCode = code
                        adminStatus.text =
                            "کد جدید ساخته شد:\n$code\nاعتبار: $days روز\n" +
                            "کد را اکنون کپی و نگهداری کن؛ بعداً قابل بازیابی نیست."
                    } else {
                        val message = response.optString(
                            "message",
                            "ساخت کد ناموفق بود."
                        )
                        adminStatus.text =
                            "خطا ($responseCode): $message"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    adminStatus.text =
                        "خطا در ارتباط با سرور هنگام ساخت کد."
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun activateCode() {
        val code = codeInput.text.toString().trim()

        if (code.length < 8) {
            codeInput.error = "کد معتبر را وارد کن"
            return
        }

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
                connection = (URL(activateEndpoint).openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("apikey", apiKey)
                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                }

                val body = JSONObject().apply {
                    put("code", code)
                    put("device_id", deviceId)
                }

                OutputStreamWriter(
                    connection.outputStream,
                    Charsets.UTF_8
                ).use { it.write(body.toString()) }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val text = stream?.bufferedReader()
                    ?.use { it.readText() } ?: "{}"

                val response = JSONObject(text)
                val success = response.optBoolean("success", false)

                runOnUiThread {
                    if (responseCode in 200..299 && success) {
                        val expiresAt =
                            response.optString("expires_at", "")
                        val remaining =
                            response.optLong("remaining_seconds", 0L)

                        if (expiresAt.isNotBlank() && remaining > 0L) {
                            prefs.edit()
                                .putBoolean("license_active", true)
                                .putString("license_device_id", deviceId)
                                .putString("license_expires_at", expiresAt)
                                .putLong(
                                    "license_remaining_seconds",
                                    remaining
                                )
                                .apply()

                            status.text =
                                "فعال‌سازی موفق شد.\nزمان باقی‌مانده: " +
                                "${remaining / 86400} روز"
                            codeInput.text.clear()
                        } else {
                            status.text =
                                "پاسخ سرور ناقص است؛ فعال‌سازی تأیید نشد."
                        }
                    } else {
                        status.text = "خطا: " +
                            response.optString(
                                "message",
                                "کد تأیید نشد."
                            )
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
