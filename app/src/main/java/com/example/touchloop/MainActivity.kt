package com.example.touchloop

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
    private val orange = Color.rgb(245, 158, 66)

    private lateinit var root: LinearLayout
    private var adminToken: String? = null
    private var generatedCode: String? = null

    private val prefs by lazy { getSharedPreferences("touch_loop", MODE_PRIVATE) }
    private val apiKey by lazy { getString(R.string.supabase_publishable_key) }

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
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun addSpace(height: Int = 10) { root.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }

    private fun addText(text: String, size: Float = 16f, color: Int = white, bold: Boolean = false, centered: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            gravity = if (centered) Gravity.CENTER else Gravity.RIGHT
            setPadding(dp(2), dp(5), dp(2), dp(5))
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun addCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(panelColor, 16)
        }
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        return card
    }

    private fun addButton(text: String, color: Int = cyan, action: () -> Unit) {
        val button = Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 15f
            setTextColor(if (color == cyan) navy else white)
            background = rounded(color, 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
    }

    private fun addField(hintText: String, inputType: Int = InputType.TYPE_CLASS_TEXT, singleLine: Boolean = true): EditText {
        return EditText(this).apply {
            hint = hintText; textSize = 15f; setTextColor(white); setHintTextColor(muted)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            this.inputType = inputType; isSingleLine = singleLine
            background = rounded(panelColor, 10)
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    }

    private fun addHeader() {
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_touch_loop_logo)
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply { gravity = Gravity.CENTER }
            contentDescription = "لوگوی Touch Loop"
        }
        root.addView(logo)
        addSpace(8)
        addText("TOUCH LOOP", 27f, white, true, true)
        addText("مدیریت و اجرای ماکروهای لمسی", 14f, muted, false, true)
        addSpace(16)
    }

    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm: Int; val jd: Int
        if (days < 186) { jm = 1 + (days / 31); jd = 1 + (days % 31) } 
        else { jm = 7 + ((days - 186) / 30); jd = 1 + ((days - 186) % 30) }
        return Triple(jy, jm, jd)
    }

    private fun formatPersianDateTime(isoDate: String): String {
        if (isoDate.isBlank() || isoDate == "—") return "—"
        return try {
            val datePart = isoDate.take(10)
            val timePart = if (isoDate.length >= 16) isoDate.substring(11, 16) else ""
            val parts = datePart.split("-")
            if (parts.size != 3) return isoDate
            val (jy, jm, jd) = gregorianToJalali(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            val persianDate = String.format("%04d/%02d/%02d", jy, jm, jd)
            if (timePart.isNotBlank()) "$persianDate $timePart" else persianDate
        } catch (_: Exception) { isoDate }
    }

    private fun openRestrictedSettings() {
        try {
            val intent = Intent("android.settings.MANAGE_APP_RESTRICTED_SETTINGS").apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "لطفاً به تنظیمات برنامه بروید", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAccessibilityService() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در باز کردن تنظیمات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHome() {
        adminToken = null
        newRoot()
        addHeader()
        
        fetchAndShowAnnouncement()
        
        if (prefs.getBoolean("license_active", false)) {
            val expiry = prefs.getString("license_expires_at", "—") ?: "—"
            addCard().apply {
                addText("✅ لایسنس فعال است", 21f, green, true, true)
                addText("تا تاریخ: ${formatPersianDateTime(expiry)}", 14f, muted, false, true)
            }
            addSpace(12)
            
            addButton("📂 مدیریت ماکروهای ذخیره‌شده") {
                showMacrosManager()
            }
            
            addButton("⚙️ فعال‌سازی سرویس دسترسی (Accessibility)", color = cyan) {
                openAccessibilityService()
            }
            
            addButton("🔓 رفع محدودیت دسترسی (Allow restricted settings)", color = orange) {
                openRestrictedSettings()
            }
            
            addSpace(8)
            addCard().apply {
                addText("📋 راهنمای دقیق فعال‌سازی (اندروید ۱۳+)", 16f, cyan, true, true)
                addText("۱. روی دکمه «⚙️ فعال‌سازی سرویس دسترسی (Accessibility)» بزنید.", 13f, white)
                addText("۲. در صفحه‌ای که باز می‌شود، وارد بخش «برنامه‌های نصب‌شده» شوید و روی «Touch Loop automation» کلیک کنید.", 13f, white)
                addText("(در این مرحله فعال نمی‌شود و خطا می‌دهد؛ این طبیعی است.)", 12f, muted)
                addText("۳. به برنامه Touch Loop برگردید.", 13f, white)
                addText("۴. حالا روی دکمه «🔓 رفع محدودیت دسترسی (Allow restricted settings)» بزنید.", 13f, white)
                addText("۵. در صفحه‌ای که باز می‌شود، روی سه نقطه (⋮) بالای صفحه بزنید و گزینه «Allow restricted settings / اجازه تنظیمات محدود» را انتخاب و تأیید کنید.", 13f, white)
                addText("۶. دوباره به برنامه برگردید و روی دکمه «⚙️ فعال‌سازی سرویس دسترسی (Accessibility)» بزنید.", 13f, white)
                addText("۷. دوباره وارد بخش «برنامه‌های نصب‌شده» شوید.", 13f, white)
                addText("۸. حالا می‌توانید «Touch Loop automation» را روشن کنید و OK را بزنید. 🎉", 13f, white)
            }
            
            addSpace(12)
            addButton("ورود به پنل مدیریت") { showAdminLogin() }
        } else {
            addCard().apply {
                addText("خوش آمدی", 21f, white, true, true)
                addText("برای ادامه، نوع ورود را انتخاب کن.", 15f, muted, false, true)
            }
            addSpace(12)
            addButton("ورود مدیر") { showAdminLogin() }
            addButton("ورود کاربر") { showUserScreen() }
        }
        addSpace(16)
        addText("برای فعال‌سازی برنامه، ورود کاربر و کد فعال‌سازی لازم است.", 13f, muted, false, true)
    }

    private fun showMacrosManager() {
        val dialog = AlertDialog.Builder(this).create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        
        val scrollView = ScrollView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFF1A1D24.toInt())
        }
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        
        container.addView(TextView(this).apply {
            text = "📂 ماکروهای ذخیره‌شده"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(16))
        })
        
        val macros = getSavedMacros()
        
        if (macros.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "هنوز ماکرویی ذخیره نشده است.\nاز پنل شناور، ماکرو ضبط کنید و سپس با نام ذخیره کنید."
                textSize = 14f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
            })
        } else {
            macros.forEach { macroName ->
                val macroView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundColor(0xFF2A2D35.toInt())
                }
                
                val nameText = TextView(this).apply {
                    text = macroName
                    textSize = 15f
                    setTextColor(cyan)
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                macroView.addView(nameText)
                
                val runBtn = Button(this).apply {
                    text = "▶ اجرا"
                    textSize = 12f
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setTextColor(white)
                    background = rounded(green, 8)
                    setOnClickListener {
                        runMacro(macroName)
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "ماکرو $macroName در حال اجرا...", Toast.LENGTH_SHORT).show()
                    }
                }
                macroView.addView(runBtn)
                
                val deleteBtn = Button(this).apply {
                    text = "🗑️"
                    textSize = 14f
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setTextColor(white)
                    background = rounded(red, 8)
                    setOnClickListener {
                        deleteMacro(macroName)
                        dialog.dismiss()
                        showMacrosManager()
                    }
                }
                macroView.addView(deleteBtn)
                
                container.addView(macroView)
            }
        }
        
        container.addView(Button(this).apply {
            text = "💾 ذخیره ماکروی فعلی با نام جدید"
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                dialog.dismiss()
                showSaveMacroDialog()
            }
        })
        
        container.addView(Button(this).apply {
            text = "بستن"
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { dialog.dismiss() }
        })
        
        scrollView.addView(container)
        dialog.setView(scrollView)
        dialog.show()
    }

    private fun showSaveMacroDialog() {
        val input = EditText(this).apply {
            hint = "نام ماکرو را وارد کنید (مثلاً: اینستاگرام)"
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        
        AlertDialog.Builder(this)
            .setTitle("ذخیره ماکروی فعلی")
            .setMessage("ماکروی ضبط‌شده فعلی با این نام ذخیره می‌شود.")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    saveMacroWithName(name)
                    Toast.makeText(this, "ماکرو '$name' ذخیره شد", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "نام معتبر نیست", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun runMacro(name: String) {
        prefs.edit()
            .putString("pending_macro_name", name)
            .putBoolean("run_pending_macro", true)
            .apply()
        Toast.makeText(this, "ماکرو '$name' برای اجرا ارسال شد", Toast.LENGTH_SHORT).show()
    }

    private fun deleteMacro(name: String) {
        deleteSavedMacro(name)
        Toast.makeText(this, "ماکرو '$name' حذف شد", Toast.LENGTH_SHORT).show()
    }

    private fun getSavedMacros(): List<String> {
        val savedMacrosJson = prefs.getString("saved_macros", "[]") ?: "[]"
        val savedMacros = JSONArray(savedMacrosJson)
        val names = mutableListOf<String>()
        for (i in 0 until savedMacros.length()) {
            val macro = savedMacros.optJSONObject(i) ?: continue
            names.add(macro.optString("name", "ماکرو ${i + 1}"))
        }
        return names
    }

    private fun saveMacroWithName(name: String) {
        val currentMacroJson = prefs.getString("current_macro", null) ?: prefs.getString("macro", null) ?: return
        val savedMacrosJson = prefs.getString("saved_macros", "[]") ?: "[]"
        val savedMacros = JSONArray(savedMacrosJson)
        
        val macroObj = JSONObject()
        macroObj.put("name", name)
        macroObj.put("steps", JSONObject(currentMacroJson).optJSONArray("steps"))
        savedMacros.put(macroObj)
        
        prefs.edit().putString("saved_macros", savedMacros.toString()).apply()
    }

    private fun deleteSavedMacro(name: String) {
        val savedMacrosJson = prefs.getString("saved_macros", "[]") ?: "[]"
        val savedMacros = JSONArray(savedMacrosJson)
        val newMacros = JSONArray()
        for (i in 0 until savedMacros.length()) {
            val macro = savedMacros.optJSONObject(i) ?: continue
            if (macro.optString("name") != name) {
                newMacros.put(macro)
            }
        }
        prefs.edit().putString("saved_macros", newMacros.toString()).apply()
    }

    private fun fetchAndShowAnnouncement() {
        runBackground(onError = { }) {
            val response = postJson(
                adminEndpoint,
                JSONObject().apply { put("action", "get_announcement") },
                useAuthToken = false
            )
            if (response.first in 200..299) {
                val announcement = response.second.optJSONObject("announcement")
                if (announcement != null) {
                    val message = announcement.optString("message", "")
                    if (message.isNotBlank()) {
                        runOnUiThread { showAnnouncementDialog(message) }
                    }
                }
            }
        }
    }

    private fun showAnnouncementDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("📢 پیام مدیر")
            .setMessage(message)
            .setPositiveButton("بستن") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showUserScreen() {
        newRoot()
        addHeader()
        addText("ورود کاربر", 23f, white, true, true)
        addSpace(10)
        val status = addText("کد فعال‌سازی را وارد کن.", 14f, muted, false, true)
        val codeInput = addField("کد فعال‌سازی").apply { contentDescription = "کد فعال‌سازی" }
        addButton("فعال‌سازی") { activateCode(codeInput, status) }
        addButton("بازگشت به صفحه اول", color = panelColor) { showHome() }
    }

    private fun showAdminLogin() {
        newRoot()
        addHeader()
        addText("ورود مدیر", 23f, white, true, true)
        addSpace(12)
        val emailInput = addField("ایمیل حساب مدیر Supabase", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val passwordInput = addField("رمز عبور مدیر", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val status = addText("با حسابی وارد شو که دسترسی مدیر آن ثبت شده است.", 14f, muted, false, true)
        addSpace(10)
        addButton("ورود به پنل مدیریت") {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (email.isBlank() || password.isBlank()) { status.text = "ایمیل و رمز عبور را وارد کن."; return@addButton }
            status.text = "در حال بررسی اطلاعات ورود..."
            runBackground(onError = { error -> status.text = "خطای اتصال: $error" }) {
                val response = postJson("$baseUrl/auth/v1/token?grant_type=password", JSONObject().apply {
                    put("email", email); put("password", password)
                }, useAuthToken = false)
                val token = response.second.optString("access_token", "")
                if (response.first !in 200..299 || token.isBlank()) {
                    val message = response.second.optString("msg", response.second.optString("message", response.second.optString("error_description", response.second.optString("error", "ورود ناموفق بود."))))
                    throw Exception(message)
                }
                runOnUiThread { adminToken = token; passwordInput.text.clear(); showAdminDashboard() }
            }
        }
        addButton("بازگشت به خانه", color = panelColor) { showHome() }
    }

    private fun showAdminDashboard() {
        if (adminToken.isNullOrBlank()) { showAdminLogin(); return }
        newRoot()
        addHeader()
        addText("پنل مدیریت", 23f, white, true, true)
        addText("ساخت، مشاهده و مدیریت کدهای فعال‌سازی", 14f, muted, false, true)
        addSpace(14)
        val daysInput = addField("مدت اعتبار به روز؛ مثلاً 30", InputType.TYPE_CLASS_NUMBER)
        val createStatus = addText("برای ساخت کد، مدت اعتبار را وارد کن.", 14f, muted, false, true)
        addButton("ساخت کد فعال‌سازی") {
            val token = adminToken
            if (token.isNullOrBlank()) { createStatus.text = "دوباره وارد حساب مدیر شو."; showAdminLogin(); return@addButton }
            val days = daysInput.text.toString().trim().toIntOrNull()
            if (days == null || days !in 1..3650) { daysInput.error = "عددی بین 1 تا 3650 وارد کن."; return@addButton }
            createStatus.text = "در حال ساخت کد..."
            runBackground(onError = { error -> createStatus.text = "خطای ساخت کد: $error" }) {
                val response = postJson(adminEndpoint, JSONObject().apply { put("action", "create"); put("duration_days", days) }, token)
                val body = response.second
                val code = body.optString("code", "")
                val success = body.optBoolean("success", false)
                if (response.first !in 200..299 || !success || code.isBlank()) {
                    throw Exception(body.optString("message", body.optString("error", "ساخت کد ناموفق بود.")))
                }
                generatedCode = code
                runOnUiThread {
                    createStatus.text = "کد جدید:\n$code\n\nاعتبار: $days روز"
                    createStatus.setTextColor(green)
                    daysInput.text.clear()
                }
            }
        }
        addButton("کپی آخرین کد ساخته‌شده") {
            val code = generatedCode
            if (code.isNullOrBlank()) { Toast.makeText(this, "هنوز کدی ساخته نشده است.", Toast.LENGTH_SHORT).show() } 
            else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Activation code", code))
                Toast.makeText(this, "کد کپی شد: $code", Toast.LENGTH_LONG).show()
            }
        }
        addButton("📢 ارسال پیام عمومی") { showCreateAnnouncementDialog() }
        addSpace(8)
        addText("فهرست کدها", 20f, white, true, true)
        addSpace(6)
        val listStatus = addText("برای دریافت اطلاعات، دکمه بارگذاری را بزن.", 14f, muted, false, true)
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addButton("بارگذاری / تازه‌سازی فهرست") { loadAdminCodes(listContainer, listStatus) }
        addButton("بازگشت به خانه", color = panelColor) { showHome() }
        loadAdminCodes(listContainer, listStatus)
    }

    private fun showCreateAnnouncementDialog() {
        val input = EditText(this).apply {
            hint = "پیام خود را بنویسید..."
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("پیام عمومی جدید")
            .setView(input)
            .setPositiveButton("ارسال") { _, _ ->
                val message = input.text.toString().trim()
                if (message.isNotBlank()) createAnnouncement(message)
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun createAnnouncement(message: String) {
        val token = adminToken
        if (token.isNullOrBlank()) { Toast.makeText(this, "ابتدا وارد شوید", Toast.LENGTH_SHORT).show(); return }
        runBackground(onError = { error -> Toast.makeText(this, "خطا: $error", Toast.LENGTH_LONG).show() }) {
            val response = postJson(adminEndpoint, JSONObject().apply { put("action", "create_announcement"); put("message", message) }, token)
            if (response.first in 200..299 && response.second.optBoolean("success", false)) {
                runOnUiThread { Toast.makeText(this, "پیام با موفقیت ارسال شد", Toast.LENGTH_SHORT).show() }
            } else {
                throw Exception(response.second.optString("message", "خطا در ارسال پیام"))
            }
        }
    }

    private fun loadAdminCodes(container: LinearLayout, status: TextView) {
        val token = adminToken
        if (token.isNullOrBlank()) { status.text = "نشست مدیر موجود نیست."; return }
        status.text = "در حال دریافت فهرست کدها..."
        container.removeAllViews()
        runBackground(onError = { error -> status.text = "خطای دریافت فهرست: $error" }) {
            val response = postJson(adminEndpoint, JSONObject().apply { put("action", "list") }, token)
            if (response.first !in 200..299) {
                throw Exception(response.second.optString("message", response.second.optString("error", "سرور فهرست کدها را برنگرداند.")))
            }
            val body = response.second
            val codes = body.optJSONArray("codes") ?: body.optJSONArray("data") ?: JSONArray()
            runOnUiThread {
                container.removeAllViews()
                var active = 0; var expired = 0; var blocked = 0; var unused = 0
                for (i in 0 until codes.length()) {
                    val item = codes.optJSONObject(i) ?: continue
                    when (item.optString("status")) {
                        "active" -> active++; "expired" -> expired++; "blocked" -> blocked++; "unused" -> unused++
                    }
                }
                status.text = "تعداد: ${codes.length()} | فعال: $active | استفاده‌نشده: $unused | منقضی: $expired | مسدود: $blocked"
                if (codes.length() == 0) {
                    val empty = TextView(this).apply { text = "هنوز کدی در فهرست وجود ندارد."; setTextColor(muted); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) }
                    container.addView(empty); return@runOnUiThread
                }
                for (i in 0 until codes.length()) {
                    val item = codes.optJSONObject(i) ?: continue
                    addCodeCard(container, item)
                }
            }
        }
    }

    private fun addCodeCard(container: LinearLayout, item: JSONObject) {
        val id = item.optString("id", "")
        val statusValue = item.optString("status", "unknown")
        val days = item.optInt("duration_days", 0)
        val created = item.optString("created_at", "—")
        val activated = item.optString("activated_at", "—")
        val expires = item.optString("expires_at", "—")
        val device = item.optString("device_id", "")
        val remaining = item.optLong("remaining_seconds", 0L)
        val codeValue = item.optString("code", "")

        val statusLabel = when (statusValue) {
            "active" -> "فعال"; "unused" -> "استفاده‌نشده"
            "expired" -> "منقضی‌شده"; "blocked" -> "مسدود"; else -> "نامشخص"
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panelColor, 14)
        }
        container.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        fun line(text: String, color: Int = white) {
            card.addView(TextView(this).apply {
                this.text = text; textSize = 13f; setTextColor(color); gravity = Gravity.RIGHT
                setPadding(0, dp(3), 0, dp(3))
            })
        }

        line("وضعیت: $statusLabel", when (statusValue) { "active" -> green; "blocked", "expired" -> red; else -> cyan })
        line("شناسه: ${id.ifBlank { "—" }}", muted)
        line("مدت اعتبار: $days روز")
        line("تاریخ ساخت: ${formatPersianDateTime(created)}", muted)
        line("تاریخ فعال‌سازی: ${formatPersianDateTime(activated)}", muted)
        line("تاریخ انقضا: ${formatPersianDateTime(expires)}", if (statusValue == "active") green else muted)
        if (statusValue == "active") line("زمان باقی‌مانده: ${remaining.coerceAtLeast(0L) / 86400} روز", green)
        if (device.isNotBlank() && device != "null") line("شناسه دستگاه: $device", muted)

        if (codeValue.isNotBlank() && codeValue != "null") {
            val codeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val codeText = TextView(this).apply {
                text = "کد: $codeValue"; textSize = 15f; setTextColor(cyan); setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            codeRow.addView(codeText)
            val copyBtn = Button(this).apply {
                text = "کپی"; textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(white); background = rounded(cyan, 8)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Activation code", codeValue))
                    Toast.makeText(this@MainActivity, "کد کپی شد", Toast.LENGTH_SHORT).show()
                }
            }
            codeRow.addView(copyBtn)
            card.addView(codeRow)
        } else {
            line("کد در پاسخ سرور موجود نبود.", muted)
        }

        if (statusValue == "blocked" || statusValue == "expired") {
            val deleteBtn = Button(this).apply {
                text = "🗑️ حذف از لیست"; textSize = 13f; isAllCaps = false; setTextColor(white)
                background = rounded(red, 10); setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { deleteCode(id, this) }
            }
            card.addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        } else if (id.isNotBlank() && (statusValue == "active" || statusValue == "unused")) {
            val actionText = if (statusValue == "blocked") "رفع مسدودی" else "مسدود کردن"
            val actionColor = if (statusValue == "blocked") green else red
            val actionButton = Button(this).apply {
                text = actionText; isAllCaps = false; setTextColor(white); background = rounded(actionColor, 10)
                setOnClickListener { setCodeBlocked(id, statusValue != "blocked", this) }
            }
            card.addView(actionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun deleteCode(id: String, button: Button) {
        val token = adminToken
        if (token.isNullOrBlank()) { Toast.makeText(this, "ابتدا دوباره وارد پنل مدیر شو.", Toast.LENGTH_SHORT).show(); return }
        button.isEnabled = false; button.text = "در حال حذف..."
        runBackground(onError = { error -> button.isEnabled = true; button.text = "🗑️ حذف از لیست"; Toast.makeText(this, "خطا در حذف: $error", Toast.LENGTH_LONG).show() }) {
            val response = postJson(adminEndpoint, JSONObject().apply { put("action", "delete"); put("id", id) }, token)
            if (response.first !in 200..299 || !response.second.optBoolean("success", false)) {
                throw Exception(response.second.optString("message", response.second.optString("error", "سرور عملیات حذف را تأیید نکرد.")))
            }
            runOnUiThread { Toast.makeText(this, "کد با موفقیت حذف شد.", Toast.LENGTH_SHORT).show(); showAdminDashboard() }
        }
    }

    private fun setCodeBlocked(id: String, block: Boolean, button: Button) {
        val token = adminToken
        if (token.isNullOrBlank()) { Toast.makeText(this, "ابتدا دوباره وارد پنل مدیر شو.", Toast.LENGTH_SHORT).show(); return }
        button.isEnabled = false; button.text = "در حال انجام..."
        runBackground(onError = { error -> button.isEnabled = true; button.text = if (block) "مسدود کردن" else "رفع مسدودی"; Toast.makeText(this, "عملیات ناموفق بود: $error", Toast.LENGTH_LONG).show() }) {
            val response = postJson(adminEndpoint, JSONObject().apply { put("action", if (block) "block" else "unblock"); put("id", id) }, token)
            if (response.first !in 200..299 || !response.second.optBoolean("success", false)) {
                throw Exception(response.second.optString("message", response.second.optString("error", "سرور عملیات را تأیید نکرد.")))
            }
            runOnUiThread { Toast.makeText(this, if (block) "کد مسدود شد." else "مسدودی کد برداشته شد.", Toast.LENGTH_SHORT).show(); showAdminDashboard() }
        }
    }

    private fun activateCode(codeInput: EditText, status: TextView) {
        val code = codeInput.text.toString().trim()
        if (code.length < 8) { codeInput.error = "کد فعال‌سازی را درست وارد کن."; return }
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        if (deviceId.length < 8) { status.text = "شناسه گوشی قابل دریافت نیست."; return }
        status.text = "در حال بررسی کد با سرور..."
        runBackground(onError = { error -> status.text = "خطای اتصال: $error" }) {
            val response = postJson(activateEndpoint, JSONObject().apply { put("code", code); put("device_id", deviceId) }, useAuthToken = false)
            val body = response.second
            if (response.first !in 200..299 || !body.optBoolean("success", false)) {
                throw Exception(body.optString("message", body.optString("error", "کد تأیید نشد.")))
            }
            val expiresAt = body.optString("expires_at", "")
            val remaining = body.optLong("remaining_seconds", 0L)
            if (expiresAt.isBlank() || remaining <= 0L) { throw Exception("پاسخ سرور ناقص است.") }
            prefs.edit().putBoolean("license_active", true).putString("license_device_id", deviceId).putString("license_expires_at", expiresAt).putLong("license_remaining_seconds", remaining).apply()
            runOnUiThread {
                status.text = "فعال‌سازی موفق بود.\nزمان باقی‌مانده: ${remaining / 86400} روز"
                status.setTextColor(green)
                codeInput.text.clear()
                Toast.makeText(this, "فعال‌سازی انجام شد.", Toast.LENGTH_SHORT).show()
                showHome()
            }
        }
    }

    private fun postJson(endpoint: String, body: JSONObject, token: String? = null, useAuthToken: Boolean = true): Pair<Int, JSONObject> {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 20000; readTimeout = 20000; doOutput = true
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject().put("message", text.ifBlank { "پاسخ قابل‌خواندن از سرور دریافت نشد." }) }
            return Pair(code, json)
        } finally { connection?.disconnect() }
    }

    private fun runBackground(onError: (String) -> Unit, work: () -> Unit) {
        Thread { try { work() } catch (e: Exception) { val message = e.message ?: "خطای نامشخص"; runOnUiThread { onError(message) } } }.start()
    }
}
