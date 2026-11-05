package com.example.touchloop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val baseUrl = "https://rkllnarnvbylpphshweu.supabase.co"
    private val activateEndpoint = "$baseUrl/functions/v1/activate-code"
    private val adminEndpoint = "$baseUrl/functions/v1/admin-codes"

    private val navy = Color.rgb(13, 25, 48)
    private val panelColor = Color.rgb(25, 41, 68)
    private val cyan = Color.rgb(63, 190, 210)
    private val white = Color.WHITE
    private val muted = Color.rgb(190, 202, 220)
    private val green = Color.rgb(60, 190, 130)
    private val red = Color.rgb(235, 90, 100)

    private lateinit var root: LinearLayout
    private var adminToken: String? = null
    private var generatedCode: String? = null
    private var busy = false

    private val prefs by lazy {
        getSharedPreferences("touch_loop", MODE_PRIVATE)
    }

    private val apiKey by lazy {
        getString(R.string.supabase_publishable_key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        showHome()
    }

    private fun newRoot(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(24))
            setBackgroundColor(navy)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(navy)
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scroll)
        return root
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun addSpace(height: Int = 10) {
        root.addView(
            View(this),
            LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        )
    }

    private fun addText(
        text: String,
        size: Float = 16f,
        color: Int = white,
        bold: Boolean = false,
        centered: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            gravity = if (centered) Gravity.CENTER else Gravity.RIGHT
            setPadding(dp(2), dp(5), dp(2), dp(5))
            root.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun addCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(panelColor, 16)
        }

        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )
        return card
    }

    // ✅ اصلاح اصلی: پارامتر action به انتها منتقل شد تا با Lambdaهای Kotlin سازگار باشد
    private fun addButton(
        text: String,
        color: Int = cyan,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(if (color == cyan) navy else white)
            background = rounded(color, 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }
        }

        root.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(9)
            }
        )
    }

    private fun addField(
        hintText: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        singleLine: Boolean = true
    ): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 15f
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            this.inputType = inputType
            isSingleLine = singleLine
            background = rounded(panelColor, 10)
            root.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            )
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun addHeader() {
        addText("◉", 42f, cyan, true, true)
        addText("TOUCH LOOP", 27f, white, true, true)
        addText(
            "مدیریت و اجرای ماکروهای لمسی",
            14f,
            muted,
            false,
            true
        )
        addSpace(20)
    }

    private fun showHome() {
        adminToken = null
        newRoot()
        addHeader()

        addCard().apply {
            addText("خوش آمدی", 21f, white, true, true)
            addText(
                "برای ادامه، نوع ورود را انتخاب کن.",
                15f,
                muted,
                false,
                true
            )
        }

        addSpace(12)

        addButton("ورود مدیر") {
            showAdminLogin()
        }

        addButton("ورود کاربر") {
            showUserScreen()
        }

        addSpace(16)

        addText(
            "برای فعال‌سازی برنامه، ورود کاربر و کد فعال‌سازی لازم است.",
            13f,
            muted,
            false,
            true
        )
    }

    private fun showUserScreen() {
        newRoot()
        addHeader()

        addText("ورود کاربر", 23f, white, true, true)
        addSpace(10)

        val status = addText(
            if (prefs.getBoolean("license_active", false)) {
                "قبلاً روی این گوشی فعال‌سازی انجام شده است. برای بررسی مجدد، کد را وارد کن."
            } else {
                "کد فعال‌سازی را وارد کن."
            },
            14f,
            muted,
            false,
            true
        )

        val codeInput = addField("کد فعال‌سازی")
        codeInput.contentDescription = "کد فعال‌سازی"

        addButton("فعال‌سازی") {
            activateCode(codeInput, status)
        }

        addButton("تنظیمات Accessibility") {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }

        // ✅ اصلاح فراخوانی با رنگ سفارشی
        addButton("پاک‌کردن ماکروی ذخیره‌شده", color = red) {
            prefs.edit().remove("macro").apply()
            Toast.makeText(
                this,
                "ماکروی ذخیره‌شده پاک شد.",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ✅ اصلاح فراخوانی با رنگ سفارشی
        addButton("بازگشت به صفحه اول", color = panelColor) {
            showHome()
        }
    }

    private fun showAdminLogin() {
        newRoot()
        addHeader()

        addText("ورود مدیر", 23f, white, true, true)
        addSpace(12)

        val emailInput = addField(
            "ایمیل حساب مدیر Supabase",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )

        val passwordInput = addField(
            "رمز عبور مدیر",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        val status = addText(
            "با حسابی وارد شو که دسترسی مدیر آن در جدول app_admins ثبت شده است.",
            14f,
            muted,
            false,
            true
        )

        addSpace(10)

        addButton("ورود به پنل مدیریت") {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (email.isBlank() || password.isBlank()) {
                status.text = "ایمیل و رمز عبور را وارد کن."
                return@addButton
            }

            status.text = "در حال بررسی اطلاعات ورود..."

            runBackground(
                onError = { error ->
                    status.text = "خطای اتصال: $error"
                }
            ) {
                val response = postJson(
                    "$baseUrl/auth/v1/token?grant_type=password",
                    JSONObject().apply {
                        put("email", email)
                        put("password", password)
                    },
                    useAuthToken = false
                )

                val token = response.second.optString("access_token", "")

                if (response.first !in 200..299 || token.isBlank()) {
                    val message = response.second.optString(
                        "msg",
                        response.second.optString(
                            "message",
                            response.second.optString(
                                "error_description",
                                response.second.optString("error", "ورود ناموفق بود.")
                            )
                        )
                    )
                    throw Exception(message)
                }

                runOnUiThread {
                    adminToken = token
                    passwordInput.text.clear()
                    showAdminDashboard()
                }
            }
        }

        // ✅ اصلاح فراخوانی با رنگ سفارشی
        addButton("بازگشت", color = panelColor) {
            showHome()
        }
    }

    private fun showAdminDashboard() {
        if (adminToken.isNullOrBlank()) {
            showAdminLogin()
            return
        }

        newRoot()
        addHeader()

        addText("پنل مدیریت", 23f, white, true, true)
        addText(
            "ساخت، مشاهده و مدیریت کدهای فعال‌سازی",
            14f,
            muted,
            false,
            true
        )

        addSpace(14)

        val daysInput = addField(
            "مدت اعتبار به روز؛ مثلاً 30",
            InputType.TYPE_CLASS_NUMBER
        )

        val createStatus = addText(
            "برای ساخت کد، مدت اعتبار را وارد کن.",
            14f,
            muted,
            false,
            true
        )

        addButton("ساخت کد فعال‌سازی") {
            val token = adminToken
            if (token.isNullOrBlank()) {
                createStatus.text = "دوباره وارد حساب مدیر شو."
                showAdminLogin()
                return@addButton
            }

            val days = daysInput.text.toString().trim().toIntOrNull()
            if (days == null || days !in 1..3650) {
                daysInput.error = "عددی بین 1 تا 3650 وارد کن."
                return@addButton
            }

            createStatus.text = "در حال ساخت کد..."

            runBackground(
                onError = { error ->
                    createStatus.text = "خطای ساخت کد: $error"
                }
            ) {
                val response = postJson(
                    adminEndpoint,
                    JSONObject().apply {
                        put("action", "create")
                        put("duration_days", days)
                    },
                    token
                )

                val body = response.second
                val code = body.optString("code", "")
                val success = body.optBoolean("success", false)

                if (response.first !in 200..299 || !success || code.isBlank()) {
                    throw Exception(
                        body.optString(
                            "message",
                            body.optString("error", "ساخت کد ناموفق بود.")
                        )
                    )
                }

                generatedCode = code

                runOnUiThread {
                    createStatus.text =
                        "کد جدید:\n$code\n\nاعتبار: $days روز\n" +
                        "این کد را اکنون کپی و در جای امن نگهداری کن."
                    daysInput.text.clear()
                    showCopyButton()
                }
            }
        }

        addButton("کپی آخرین کد ساخته‌شده") {
            val code = generatedCode
            if (code.isNullOrBlank()) {
                createStatus.text = "هنوز کدی در این نشست ساخته نشده است."
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Activation code", code)
                )

                Toast.makeText(
                    this,
                    "کد کپی شد.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        addSpace(8)
        addText("فهرست کدها", 20f, white, true, true)
        addSpace(6)

        val listStatus = addText(
            "برای دریافت اطلاعات، دکمه بارگذاری را بزن.",
            14f,
            muted,
            false,
            true
        )

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            listContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        addButton("بارگذاری / تازه‌سازی فهرست") {
            loadAdminCodes(listContainer, listStatus)
        }

        // ✅ اصلاح فراخوانی با رنگ سفارشی
        addButton("خروج از حساب مدیر", color = red) {
            adminToken = null
            showHome()
        }

        loadAdminCodes(listContainer, listStatus)
    }

    private fun showCopyButton() {
        // دکمه کپی در صفحه پنل وجود دارد؛ این تابع برای سازگاری رابط نگه داشته شده است.
    }

    private fun loadAdminCodes(
        container: LinearLayout,
        status: TextView
    ) {
        val token = adminToken
        if (token.isNullOrBlank()) {
            status.text = "نشست مدیر موجود نیست؛ دوباره وارد شو."
            return
        }

        status.text = "در حال دریافت فهرست کدها..."
        container.removeAllViews()

        runBackground(
            onError = { error ->
                status.text = "خطای دریافت فهرست: $error"
            }
        ) {
            val response = postJson(
                adminEndpoint,
                JSONObject().apply {
                    put("action", "list")
                },
                token
            )

            if (response.first !in 200..299) {
                throw Exception(
                    response.second.optString(
                        "message",
                        response.second.optString(
                            "error",
                            "سرور فهرست کدها را برنگرداند."
                        )
                    )
                )
            }

            val body = response.second
            val codes = body.optJSONArray("codes")
                ?: body.optJSONArray("data")
                ?: JSONArray()

            runOnUiThread {
                container.removeAllViews()

                var active = 0
                var expired = 0
                var blocked = 0
                var unused = 0

                for (i in 0 until codes.length()) {
                    val item = codes.optJSONObject(i) ?: continue
                    when (item.optString("status")) {
                        "active" -> active++
                        "expired" -> expired++
                        "blocked" -> blocked++
                        "unused" -> unused++
                    }
                }

                status.text =
                    "تعداد: ${codes.length()}  |  فعال: $active  |  " +
                    "استفاده‌نشده: $unused  |  منقضی: $expired  |  " +
                    "مسدود: $blocked"

                if (codes.length() == 0) {
                    val empty = TextView(this).apply {
                        text = "هنوز کدی در فهرست وجود ندارد."
                        setTextColor(muted)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(18), 0, dp(18))
                    }
                    container.addView(empty)
                    return@runOnUiThread
                }

                for (i in 0 until codes.length()) {
                    val item = codes.optJSONObject(i) ?: continue
                    addCodeCard(container, item)
                }
            }
        }
    }

    private fun addCodeCard(
        container: LinearLayout,
        item: JSONObject
    ) {
        val id = item.optString("id", "")
        val statusValue = item.optString("status", "unknown")
        val days = item.optInt("duration_days", 0)
        val created = item.optString("created_at", "—")
        val activated = item.optString("activated_at", "—")
        val expires = item.optString("expires_at", "—")
        val device = item.optString("device_id", "")
        val remaining = item.optLong("remaining_seconds", 0L)

        val statusLabel = when (statusValue) {
            "active" -> "فعال"
            "unused" -> "استفاده‌نشده"
            "expired" -> "منقضی‌شده"
            "blocked" -> "مسدود"
            else -> "نامشخص"
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panelColor, 14)
        }

        container.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        fun line(text: String, color: Int = white) {
            card.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(color)
                gravity = Gravity.RIGHT
                setPadding(0, dp(3), 0, dp(3))
            })
        }

        line("وضعیت: $statusLabel", when (statusValue) {
            "active" -> green
            "blocked", "expired" -> red
            else -> cyan
        })

        line("شناسه: ${id.ifBlank { "—" }}", muted)
        line("مدت اعتبار: $days روز")
        line("تاریخ ساخت: $created", muted)
        line("تاریخ فعال‌سازی: $activated", muted)
        line("تاریخ انقضا: $expires", muted)

        if (statusValue == "active") {
            line(
                "زمان باقی‌مانده تقریبی: ${remaining.coerceAtLeast(0L) / 86400} روز",
                green
            )
        }

        if (device.isNotBlank() && device != "null") {
            line("شناسه دستگاه: $device", muted)
        }

        line(
            "برای کدهای قبلی، متن اصلی کد از پایگاه داده قابل بازیابی نیست.",
            muted
        )

        if (id.isNotBlank() &&
            (statusValue == "blocked" ||
                    statusValue == "active" ||
                    statusValue == "unused")
        ) {
            val actionText =
                if (statusValue == "blocked") "رفع مسدودی" else "مسدود کردن"

            val actionColor =
                if (statusValue == "blocked") green else red

            val actionButton = Button(this).apply {
                text = actionText
                isAllCaps = false
                setTextColor(white)
                background = rounded(actionColor, 10)
                setOnClickListener {
                    setCodeBlocked(
                        id,
                        statusValue != "blocked",
                        this
                    )
                }
            }

            card.addView(
                actionButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            )
        }
    }

    private fun setCodeBlocked(
        id: String,
        block: Boolean,
        button: Button
    ) {
        val token = adminToken
        if (token.isNullOrBlank()) {
            Toast.makeText(
                this,
                "ابتدا دوباره وارد پنل مدیر شو.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        button.isEnabled = false
        button.text = "در حال انجام..."

        runBackground(
            onError = { error ->
                button.isEnabled = true
                button.text = if (block) "مسدود کردن" else "رفع مسدودی"
                Toast.makeText(
                    this,
                    "عملیات ناموفق بود: $error",
                    Toast.LENGTH_LONG
                ).show()
            }
        ) {
            val response = postJson(
                adminEndpoint,
                JSONObject().apply {
                    put("action", if (block) "block" else "unblock")
                    put("id", id)
                },
                token
            )

            if (response.first !in 200..299 ||
                !response.second.optBoolean("success", false)
            ) {
                throw Exception(
                    response.second.optString(
                        "message",
                        response.second.optString(
                            "error",
                            "سرور عملیات را تأیید نکرد."
                        )
                    )
                )
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    if (block) "کد مسدود شد." else "مسدودی کد برداشته شد.",
                    Toast.LENGTH_SHORT
                ).show()

                showAdminDashboard()
            }
        }
    }

    private fun activateCode(
        codeInput: EditText,
        status: TextView
    ) {
        val code = codeInput.text.toString().trim()

        if (code.length < 8) {
            codeInput.error = "کد فعال‌سازی را درست وارد کن."
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

        runBackground(
            onError = { error ->
                status.text = "خطای اتصال: $error"
            }
        ) {
            val response = postJson(
                activateEndpoint,
                JSONObject().apply {
                    put("code", code)
                    put("device_id", deviceId)
                },
                useAuthToken = false
            )

            val body = response.second
            if (response.first !in 200..299 ||
                !body.optBoolean("success", false)
            ) {
                throw Exception(
                    body.optString(
                        "message",
                        body.optString("error", "کد تأیید نشد.")
                    )
                )
            }

            val expiresAt = body.optString("expires_at", "")
            val remaining = body.optLong("remaining_seconds", 0L)

            if (expiresAt.isBlank() || remaining <= 0L) {
                throw Exception("پاسخ سرور ناقص است؛ فعال‌سازی تأیید نشد.")
            }

            prefs.edit()
                .putBoolean("license_active", true)
                .putString("license_device_id", deviceId)
                .putString("license_expires_at", expiresAt)
                .putLong("license_remaining_seconds", remaining)
                .apply()

            runOnUiThread {
                status.text =
                    "فعال‌سازی موفق بود.\nزمان باقی‌مانده: ${remaining / 86400} روز"
                codeInput.text.clear()
                Toast.makeText(
                    this,
                    "فعال‌سازی انجام شد.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun postJson(
        endpoint: String,
        body: JSONObject,
        token: String? = null,
        useAuthToken: Boolean = true
    ): Pair<Int, JSONObject> {
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(endpoint).openConnection()
                    as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")

                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                } else if (useAuthToken) {
                    // درخواست‌های کاربر با کلید عمومی انجام می‌شوند.
                }
            }

            OutputStreamWriter(
                connection.outputStream,
                Charsets.UTF_8
            ).use { it.write(body.toString()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val text = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject().put(
                    "message",
                    text.ifBlank { "پاسخ قابل‌خواندن از سرور دریافت نشد." }
                )
            }

            return Pair(code, json)
        } finally {
            connection?.disconnect()
        }
    }

    private fun runBackground(
        onError: (String) -> Unit,
        work: () -> Unit
    ) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                val message = e.message ?: "خطای نامشخص"
                runOnUiThread { onError(message) }
            }
        }.start()
    }
}
