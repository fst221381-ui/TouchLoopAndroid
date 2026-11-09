package com.example.touchloop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class MacroAccessibilityService : AccessibilityService() {

    /**
     * TAP   : لمس در نقطه‌ی (x, y)
     * SWIPE : کشیدن از (x, y) تا (x2, y2) در مدت duration  (همان اسکرول)
     * BACK  : دکمه‌ی بازگشت
     */
    data class Step(
        val kind: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val delay: Long = 350L,
        val duration: Long = 0L
    )

    private val handler = Handler(Looper.getMainLooper())
    private val steps = mutableListOf<Step>()

    private var recording = false
    private var playing = false
    private var playbackToken = 0
    private var playIndex = 0
    private var loopCount = 0
    private var lastEventAt = 0L

    private var panel: View? = null
    private var bubble: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelCollapsed = false

    private var touchLockOverlay: View? = null
    private var userWantsTouchLocked = false
    private var isCurrentlyLocked = false
    private var lockButton: Button? = null
    private var isLicenseBlocked = false

    private var panelWidthDp = 220
    private var panelHeightDp = 320

    private var playbackSpeed = 1.0f
    private var speedLabel: TextView? = null
    private var status: TextView? = null
    private var executionReport: TextView? = null
    private var pointPicker: View? = null

    private lateinit var wm: WindowManager

    private val minPanelWidthDp = 140
    private val minPanelHeightDp = 180

    private val maxStepRetries = 20
    private var watchdogRunnable: Runnable? = null
    private var currentAttempt = 0L

    // ---------- هماهنگی در پخش ----------
    private var expectedPkg: String? = null
    private var pkgMismatch = 0
    private var lastUiChangeAt = 0L

    // حداقل زمان انتظار بعد از هر عمل (در سرعت ۱×؛ با سرعت پخش تقسیم می‌شود)
    private val tapSettleMin = 60L
    private val swipeSettleMin = 60L
    private val backSettleMin = 150L
    // اگر این مدت هیچ تغییر UI نیامد، صفحه آرام حساب می‌شود
    private val settleQuiet = 50L
    // سقف انتظار برای آرام شدن صفحه
    private val settleMax = 1200L
    // مکث بین دو دور
    private val loopGap = 150L
    // سقف مکث قبل از هر مرحله (زمان ضبط‌شده شامل مکث انسانی است)
    private val maxPreStepDelay = 150L

    // ---------- اسکرول ----------
    // true: انگشت در انتهای کشیدن کمی ثابت می‌ماند تا fling (ادامه‌ی خودکار اسکرول) رخ ندهد
    // و اسکرول در هر بار دقیقاً یکسان باشد (اندروید ۸+). false: رفتار طبیعی همراه با fling.
    private val suppressFling = true
    private val scrollHoldDuration = 60L

    // ---------- ضبط دقیق لمس ----------
    private var captureOverlay: View? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var capDownX = 0f
    private var capDownY = 0f
    private var capLastX = 0f
    private var capLastY = 0f
    private var capDownT = 0L
    private var capMaxDist = 0f
    private var capMulti = false
    private var capPreDelay = 350L

    private val powerManager: PowerManager? by lazy { getSystemService(POWER_SERVICE) as? PowerManager }

    private val licenseDateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val baseUrl = "https://rkllnarnvbylpphshweu.supabase.co"
    private val checkLicenseEndpoint = "$baseUrl/functions/v1/check-license"
    private val apiKey = "sb_publishable_t8cRQM_w5THIwJvgXrYJNg_Fbxoaz6_"

    private var lastOnlineCheck = 0L
    private val checkInterval = 2 * 60 * 1000L

    private fun isScreenOn(): Boolean = powerManager?.isInteractive ?: true

    private fun hasValidLicense(): Boolean {
        if (isLicenseBlocked) return false
        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)
        if (!prefs.getBoolean("license_active", false)) return false
        val currentDevice = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: return false
        if (prefs.getString("license_device_id", "") != currentDevice) return false
        val expiryText = prefs.getString("license_expires_at", null) ?: return false
        return try {
            val expiry = licenseDateFormatter.parse(expiryText.take(19)) ?: return false
            val isValid = System.currentTimeMillis() < expiry.time
            if (!isValid) {
                isLicenseBlocked = true
                prefs.edit().putBoolean("license_active", false).apply()
            }
            isValid
        } catch (_: Exception) { false }
    }

    private fun checkLicenseStatusOnline(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastOnlineCheck) < checkInterval) return
        lastOnlineCheck = now

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: return

        Thread {
            try {
                val url = java.net.URL(checkLicenseEndpoint)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setRequestProperty("apikey", apiKey)
                connection.setRequestProperty("Content-Type", "application/json")

                val body = JSONObject().apply { put("device_id", deviceId) }
                java.io.OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val responseJson = JSONObject(responseText)

                if (responseJson.optBoolean("success", false)) {
                    val isValid = responseJson.optBoolean("is_valid", false)
                    val isBlocked = responseJson.optBoolean("is_blocked", false)
                    val isExpired = responseJson.optBoolean("is_expired", false)

                    if (!isValid || isBlocked || isExpired) {
                        isLicenseBlocked = true
                        getSharedPreferences("touch_loop", MODE_PRIVATE).edit().putBoolean("license_active", false).apply()
                        handler.post {
                            stopAll()
                            removePanel()
                            hideTouchLock()
                        }
                    }
                }
                connection.disconnect()
            } catch (_: Exception) { }
        }.start()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        checkLicenseStatusOnline(force = true)
        if (!hasValidLicense()) { stopAll(); return }
        loadMacro()
        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)
        playbackSpeed = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.25f, 8.0f)
        panelWidthDp = prefs.getInt("panel_width_dp", 220).coerceIn(minPanelWidthDp, 450)
        panelHeightDp = prefs.getInt("panel_height_dp", 320).coerceIn(minPanelHeightDp, 800)
        showPanel()
        updateSpeedLabel()
        schedulePeriodicCheck()
    }

    private fun schedulePeriodicCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (hasValidLicense()) {
                    checkLicenseStatusOnline(force = true)
                    handler.postDelayed(this, checkInterval)
                }
            }
        }, checkInterval)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!hasValidLicense()) {
            recording = false
            hideCaptureOverlay()
            stopPlayback()
            return
        }
        if (event == null || !playing) return

        // در حال پخش: تغییرات UI برنامه‌ی هدف را ثبت کن تا بدانیم صفحه کی آرام شده است
        val t = event.eventType
        if ((t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                    t == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
            event.packageName?.toString() != packageName) {
            lastUiChangeAt = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() { stopAll() }

    private fun addRecordedStep(step: Step) {
        if (!hasValidLicense()) return
        steps.add(step)
        lastEventAt = System.currentTimeMillis()
        status?.text = if (recording) "در حال ضبط • ${steps.size} مرحله" else "${steps.size} مرحله"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun scaleFactor(): Float = (panelWidthDp / 220f).coerceIn(0.6f, 1.4f)
    private fun scaledDp(value: Int): Int = dp((value * scaleFactor()).toInt().coerceAtLeast(1))

    private fun updateKeepScreenOnFlag(on: Boolean) {
        panelParams?.let { p ->
            val newFlags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            if (newFlags != p.flags) {
                p.flags = newFlags
                panel?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) { } }
            }
        }
        bubbleParams?.let { p ->
            val newFlags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            if (newFlags != p.flags) {
                p.flags = newFlags
                bubble?.let { try { wm.updateViewLayout(it, p) } catch (_: Exception) { } }
            }
        }
    }

    // ======================= پنل =======================

    private fun showPanel() {
        if (!hasValidLicense() || panel != null) return
        val scale = scaleFactor()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(scaledDp(8), scaledDp(6), scaledDp(8), scaledDp(6))
            setBackgroundColor(0xF221252D.toInt())
        }

        val header = TextView(this).apply {
            text = "☰ TOUCH LOOP"
            textSize = 13f * scale
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(scaledDp(8), scaledDp(8), scaledDp(8), scaledDp(8))
            setBackgroundColor(0xFF30394A.toInt())
            contentDescription = "برای جابه‌جایی پنل بکشید"
        }

        status = TextView(this).apply {
            text = if (isLicenseBlocked) "کد مسدود شده است" else "Touch Loop آماده است"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f * scale
            gravity = Gravity.CENTER
            setPadding(scaledDp(4), scaledDp(6), scaledDp(4), scaledDp(6))
        }

        executionReport = TextView(this).apply {
            text = ""
            setTextColor(0xFFB0C4DE.toInt())
            textSize = 10f * scale
            gravity = Gravity.CENTER
            setPadding(scaledDp(4), scaledDp(2), scaledDp(4), scaledDp(2))
            visibility = View.GONE
        }

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f * scale
            minimumHeight = scaledDp(32)
            setPadding(scaledDp(4), scaledDp(2), scaledDp(4), scaledDp(2))
            isAllCaps = false
            setOnClickListener {
                if (!hasValidLicense()) {
                    recording = false
                    hideCaptureOverlay()
                    stopPlayback()
                    status?.text = "کد شما مسدود یا منقضی شده است"
                    removePanel()
                    return@setOnClickListener
                }
                action()
            }
        }

        var panelDragData: FloatArray? = null
        header.setOnTouchListener { _, event ->
            if (!hasValidLicense()) { removePanel(); return@setOnTouchListener true }
            val params = panelParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    panelDragData = floatArrayOf(event.rawX, event.rawY, params.x.toFloat(), params.y.toFloat())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val data = panelDragData ?: return@setOnTouchListener true
                    val dx = (event.rawX - data[0]).toInt()
                    val dy = (event.rawY - data[1]).toInt()
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    params.x = (data[2].toInt() + dx).coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                    params.y = (data[3].toInt() + dy).coerceIn(0, (screenHeight - dp(60)).coerceAtLeast(0))
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { panelDragData = null; true }
                else -> true
            }
        }

        val scrollView = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        val contentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

        contentContainer.addView(status)
        contentContainer.addView(executionReport)

        contentContainer.addView(button("● ضبط") {
            checkLicenseStatusOnline(force = true)
            if (!hasValidLicense()) return@button
            stopPlayback()
            steps.clear()
            temporarilyUnlockForPlayback()
            removePointPicker()
            recording = true
            lastEventAt = 0L
            executionReport?.visibility = View.GONE
            showCaptureOverlay()
            status?.text = "در حال ضبط • لمس‌ها دقیق ثبت می‌شوند"
        })
        contentContainer.addView(button("＋ کلیک") {
            if (!hasValidLicense()) return@button
            if (!recording || playing) status?.text = "ابتدا ضبط را شروع کن" else showPointPicker()
        })
        contentContainer.addView(button("↩ بازگشت") {
            if (!hasValidLicense()) return@button
            if (recording && !playing) {
                addRecordedStep(Step(kind = "BACK"))
                performGlobalAction(GLOBAL_ACTION_BACK)
            } else if (playing) { status?.text = "برای توقف، Stop را بزن" }
            else { performGlobalAction(GLOBAL_ACTION_BACK) }
        })
        contentContainer.addView(button("■ توقف") {
            if (!hasValidLicense()) return@button
            recording = false
            hideCaptureOverlay()
            removePointPicker()
            stopPlayback()
            saveMacro()
            restoreLockAfterPlayback()
            status?.text = "ذخیره شد • ${steps.size} مرحله"
            executionReport?.visibility = View.GONE
        })
        contentContainer.addView(button("▶ پخش") {
            checkLicenseStatusOnline(force = true)
            if (!hasValidLicense()) return@button
            recording = false
            hideCaptureOverlay()
            removePointPicker()
            stopPlayback()
            saveMacro()
            if (steps.isEmpty()) { status?.text = "ابتدا ماکرو ضبط کن" }
            else {
                playbackToken++; loopCount = 1; playing = true; playIndex = 0
                expectedPkg = currentPackage()
                pkgMismatch = 0
                lastUiChangeAt = System.currentTimeMillis()
                status?.text = "در حال پخش…"
                updateExecutionReport()
                executionReport?.visibility = View.VISIBLE
                temporarilyUnlockForPlayback()
                updateKeepScreenOnFlag(true)
                runNext(playbackToken)
            }
        })
        contentContainer.addView(button("✎ ویرایش") {
            if (!hasValidLicense()) return@button
            if (playing) { status?.text = "ابتدا پخش را متوقف کن"; return@button }
            if (recording) { status?.text = "ابتدا ضبط را متوقف کن"; return@button }
            if (steps.isEmpty()) status?.text = "ماکرویی برای ویرایش نیست" else showMacroEditor()
        })

        lockButton = button(if (userWantsTouchLocked) "🔓 باز کردن قفل" else "🔒 قفل صفحه") {
            if (!hasValidLicense()) return@button
            if (userWantsTouchLocked) {
                userWantsTouchLocked = false
                hideTouchLock()
                lockButton?.text = "🔒 قفل صفحه"
            } else {
                userWantsTouchLocked = true
                showTouchLock()
                lockButton?.text = "🔓 باز کردن قفل"
            }
        }.also { contentContainer.addView(it) }

        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        speedLabel = TextView(this).apply {
            textSize = 11f * scale
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(scaledDp(4), scaledDp(4), scaledDp(4), scaledDp(4))
        }
        speedRow.addView(button("−") { playbackSpeed = (playbackSpeed / 1.25f).coerceAtLeast(0.25f); updateSpeedLabel() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        speedRow.addView(speedLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
        speedRow.addView(button("+") { playbackSpeed = (playbackSpeed * 1.25f).coerceAtMost(8.0f); updateSpeedLabel() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        contentContainer.addView(speedRow)

        contentContainer.addView(button("◉ حباب") { collapseToBubble() })
        contentContainer.addView(button("× بستن") { removePanel() })

        scrollView.addView(contentContainer)
        root.addView(header)
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val resizeHandle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 14f * scale
            setTextColor(0xFF3FBED2.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0x00000000)
            setPadding(scaledDp(6), scaledDp(4), scaledDp(6), scaledDp(4))
            contentDescription = "برای تغییر اندازه پنل بکشید"
        }

        var resizeData: FloatArray? = null
        resizeHandle.setOnTouchListener { _, event ->
            if (!hasValidLicense()) return@setOnTouchListener true
            if (panelParams == null) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeData = floatArrayOf(event.rawX, event.rawY, panelWidthDp.toFloat(), panelHeightDp.toFloat())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val data = resizeData ?: return@setOnTouchListener true
                    val dx = ((event.rawX - data[0]) / resources.displayMetrics.density).toInt()
                    val dy = ((event.rawY - data[1]) / resources.displayMetrics.density).toInt()
                    panelWidthDp = (data[2].toInt() + dx).coerceIn(minPanelWidthDp, 450)
                    panelHeightDp = (data[3].toInt() + dy).coerceIn(minPanelHeightDp, 800)
                    updatePanelSize()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { resizeData = null; true }
                else -> true
            }
        }

        val handleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        handleRow.addView(resizeHandle)
        root.addView(handleRow)

        val width = dp(panelWidthDp)
        val height = dp(panelHeightDp)

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(140) }

        panel = root
        panelParams = params
        try { wm.addView(root, params); updateSpeedLabel(); updateKeepScreenOnFlag(playing) } catch (_: Exception) { panel = null; panelParams = null }
    }

    private fun updateExecutionReport() {
        if (!playing || steps.isEmpty()) { executionReport?.visibility = View.GONE; return }
        val current = playIndex + 1
        val total = steps.size
        executionReport?.text = "دور $loopCount • مرحله $current از $total (${stepTypeName(steps.getOrNull(playIndex)?.kind)})"
    }

    private fun stepTypeName(kind: String?): String = when (kind) {
        "TAP" -> "کلیک"
        "SWIPE" -> "اسکرول"
        "BACK" -> "بازگشت"
        else -> "نامشخص"
    }

    private fun bringControlsToFront() {
        try {
            val p = panel
            val pp = panelParams
            if (p != null && pp != null) { wm.removeView(p); wm.addView(p, pp) }
            val b = bubble
            val bp = bubbleParams
            if (b != null && bp != null) { wm.removeView(b); wm.addView(b, bp) }
        } catch (_: Exception) { }
    }

    // ======================= ضبط دقیق لمس =======================

    /** یک لایه‌ی نیمه‌شفاف روی صفحه که مختصات واقعی لمس را می‌گیرد و همان را اجرا می‌کند */
    private fun showCaptureOverlay() {
        if (captureOverlay != null) return
        val v = View(this).apply {
            setBackgroundColor(0x12FF3B30)
            setOnTouchListener { _, e -> handleCaptureTouch(e); true }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(v, params)
            captureOverlay = v
            captureParams = params
            bringControlsToFront()
        } catch (_: Exception) {
            captureOverlay = null
            captureParams = null
            recording = false
            status?.text = "ضبط شروع نشد"
        }
    }

    private fun hideCaptureOverlay() {
        captureOverlay?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        captureOverlay = null
        captureParams = null
    }

    private fun setCaptureTouchable(touchable: Boolean) {
        val v = captureOverlay ?: return
        val p = captureParams ?: return
        p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(v, p) } catch (_: Exception) { }
    }

    private fun handleCaptureTouch(e: MotionEvent) {
        if (!hasValidLicense()) { removePanel(); return }
        if (!recording || playing) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                capDownX = e.rawX; capDownY = e.rawY
                capLastX = e.rawX; capLastY = e.rawY
                capDownT = e.eventTime
                capMaxDist = 0f
                capMulti = false
                val now = System.currentTimeMillis()
                capPreDelay = if (lastEventAt == 0L) 350L else (now - lastEventAt).coerceIn(120L, 5000L)
            }
            MotionEvent.ACTION_POINTER_DOWN -> capMulti = true
            MotionEvent.ACTION_MOVE -> {
                if (!capMulti) {
                    capLastX = e.rawX; capLastY = e.rawY
                    capMaxDist = max(capMaxDist, hypot(capLastX - capDownX, capLastY - capDownY))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (capMulti) return
                capLastX = e.rawX; capLastY = e.rawY
                capMaxDist = max(capMaxDist, hypot(capLastX - capDownX, capLastY - capDownY))
                val tapSlop = ViewConfiguration.get(this).scaledTouchSlop * 2f
                val step = if (capMaxDist <= tapSlop) {
                    Step(kind = "TAP", x = capDownX, y = capDownY, delay = capPreDelay)
                } else {
                    Step(
                        kind = "SWIPE", x = capDownX, y = capDownY, x2 = capLastX, y2 = capLastY,
                        delay = capPreDelay, duration = (e.eventTime - capDownT).coerceIn(80L, 3000L)
                    )
                }
                addRecordedStep(step)
                replayLive(step)
            }
            else -> { }
        }
    }

    /** لمس ضبط‌شده را همان لحظه (مثل پخش) روی صفحه اجرا می‌کند؛ پس آنچه می‌بینید همان است که پخش می‌شود */
    private fun replayLive(step: Step) {
        setCaptureTouchable(false)
        handler.postDelayed({
            if (!hasValidLicense()) { setCaptureTouchable(true); return@postDelayed }
            performStep(step) {
                handler.postDelayed({
                    setCaptureTouchable(true)
                    lastEventAt = System.currentTimeMillis()
                }, 40L)
            }
        }, 80L)
    }

    // ======================= اجرای لمس =======================

    /** اجرای یک مرحله‌ی لمسی (TAP یا SWIPE)؛ done(true) یعنی انجام شد */
    private fun performStep(step: Step, done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 24) {
            performTouchStep(step, done)
            return
        }
        // اندروید ۶: فقط از طریق گره‌های دسترسی‌پذیری
        val root = rootInActiveWindow
        try {
            if (step.kind == "SWIPE") {
                val forward = step.y2 < step.y
                val ok = performScrollAtPoint(root, step.x.toInt(), step.y.toInt(), forward) || performScrollOnNode(root, forward)
                if (!ok) status?.text = "این صفحه اسکرول قابل‌دسترسی ندارد"
            } else {
                if (!clickNodeAt(root, step.x.toInt(), step.y.toInt())) status?.text = "این کلیک در اندروید ۶ قابل اجرا نیست"
            }
        } finally { root?.recycle() }
        done(true)
    }

    @android.annotation.TargetApi(24)
    private fun performTouchStep(step: Step, done: (Boolean) -> Unit) {
        val isSwipe = step.kind == "SWIPE"
        val path = Path()
        val dur: Long
        if (isSwipe) {
            path.moveTo(step.x, step.y)
            path.lineTo(step.x2, step.y2)
            dur = step.duration.coerceIn(80L, 3000L)
        } else {
            path.moveTo(step.x, step.y)
            dur = 70L
        }

        val stroke = if (isSwipe && suppressFling && Build.VERSION.SDK_INT >= 26) {
            GestureDescription.StrokeDescription(path, 0, dur, true)
        } else {
            GestureDescription.StrokeDescription(path, 0, dur)
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isSwipe && suppressFling && Build.VERSION.SDK_INT >= 26) {
                    holdSwipeEnd(stroke, step.x2, step.y2, done)
                } else {
                    done(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) { done(false) }
        }, null)
        if (!accepted) done(false)
    }

    /** انگشت در نقطه‌ی پایان کمی ثابت می‌ماند و بعد رها می‌شود تا fling رخ ندهد */
    @android.annotation.TargetApi(26)
    private fun holdSwipeEnd(first: GestureDescription.StrokeDescription, x: Float, y: Float, done: (Boolean) -> Unit) {
        val holdPath = Path().apply { moveTo(x, y) }
        val second = first.continueStroke(holdPath, 0L, scrollHoldDuration, false)
        val gesture = GestureDescription.Builder().addStroke(second).build()
        val ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done(true) }
            // کشیدن اصلی انجام شده؛ تکرار نمی‌کنیم
            override fun onCancelled(gestureDescription: GestureDescription?) { done(true) }
        }, null)
        if (!ok) done(true)
    }

    // ======================= پخش =======================

    private fun currentPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try { root.packageName?.toString() } finally { root.recycle() }
    }

    /** پخش را متوقف می‌کند و پیام می‌دهد (هیچ مرحله‌ای رد نمی‌شود) */
    private fun failPlayback(message: String) {
        stopPlayback()
        status?.text = message
    }

    /** منتظر می‌ماند تا حداقل minWait بگذرد و صفحه آرام شود (با سقف settleMax) */
    private fun waitForSettle(session: Int, minWait: Long, onReady: () -> Unit) {
        val startedAt = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (!playing || session != playbackToken) return
                val now = System.currentTimeMillis()
                val elapsed = now - startedAt
                val idle = now - lastUiChangeAt
                if (elapsed >= settleMax || (elapsed >= minWait && idle >= settleQuiet)) {
                    onReady()
                } else {
                    handler.postDelayed(this, 25L)
                }
            }
        }
        handler.postDelayed(poll, 25L)
    }

    private fun afterAction(kind: String, index: Int, session: Int) {
        lastUiChangeAt = System.currentTimeMillis()
        val base = when (kind) {
            "SWIPE" -> swipeSettleMin
            "BACK" -> backSettleMin
            else -> tapSettleMin
        }
        val minWait = (base / playbackSpeed).toLong().coerceIn(30L, 2000L)
        waitForSettle(session, minWait) {
            if (hasValidLicense() && playing && session == playbackToken) runStep(index + 1, session, 0)
        }
    }

    private fun runNext(session: Int) {
        runStep(playIndex, session, 0)
    }

    private fun runStep(index: Int, session: Int, retry: Int) {
        if (!hasValidLicense()) {
            stopPlayback()
            restoreLockAfterPlayback()
            status?.text = "کد مسدود یا منقضی شده است"
            removePanel()
            return
        }
        if (!playing || session != playbackToken || steps.isEmpty()) return

        if (index >= steps.size) {
            loopCount++
            playIndex = 0
            handler.postDelayed({
                if (hasValidLicense() && playing && session == playbackToken) runStep(0, session, 0)
            }, loopGap)
            return
        }

        playIndex = index
        updateExecutionReport()
        val step = steps[index]
        val delay = (minOf(step.delay, maxPreStepDelay) / playbackSpeed).toLong().coerceIn(20L, 2000L)
        handler.postDelayed({
            if (!hasValidLicense()) { stopPlayback(); return@postDelayed }
            if (!playing || session != playbackToken) return@postDelayed

            if (!isScreenOn()) {
                status?.text = "صفحه خاموش است؛ منتظر روشن‌شدن…"
                handler.postDelayed({
                    if (playing && session == playbackToken) runStep(index, session, retry)
                }, 1000L)
                return@postDelayed
            }

            // اگر از برنامه‌ی هدف (بله) خارج شدیم، ادامه نده
            val expected = expectedPkg
            if (expected != null) {
                val cur = currentPackage()
                if (cur != null && cur != expected) {
                    pkgMismatch++
                    if (pkgMismatch > 6) {
                        failPlayback("از برنامه خارج شد؛ پخش متوقف شد")
                        return@postDelayed
                    }
                    status?.text = "منتظر بازگشت به برنامه…"
                    handler.postDelayed({
                        if (playing && session == playbackToken) runStep(index, session, retry)
                    }, 400L)
                    return@postDelayed
                } else {
                    pkgMismatch = 0
                }
            }

            when (step.kind) {
                "BACK" -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    afterAction("BACK", index, session)
                }
                "TAP", "SWIPE" -> dispatchStep(step, index, session, retry)
                else -> runStep(index + 1, session, 0)
            }
        }, delay)
    }

    private fun dispatchStep(step: Step, index: Int, session: Int, retry: Int) {
        if (!hasValidLicense()) { stopPlayback(); return }
        if (!playing || session != playbackToken) return

        currentAttempt++
        val attempt = currentAttempt
        armWatchdog(step, index, session, retry, attempt, 15000L + step.duration)

        performStep(step) { ok ->
            if (attempt != currentAttempt) return@performStep
            disarmWatchdog()
            if (!playing || session != playbackToken) return@performStep
            if (ok) {
                afterAction(step.kind, index, session)
            } else if (retry < maxStepRetries) {
                val backoff = if (retry < 8) 300L else 1000L
                status?.text = "تداخل لمس؛ تلاش مجدد ${retry + 1}"
                handler.postDelayed({
                    if (hasValidLicense() && playing && session == playbackToken) dispatchStep(step, index, session, retry + 1)
                }, backoff)
            } else {
                failPlayback("مرحله ${index + 1} ناموفق بود؛ پخش متوقف شد")
            }
        }
    }

    private fun armWatchdog(step: Step, index: Int, session: Int, retry: Int, attempt: Long, timeout: Long) {
        disarmWatchdog()
        val r = Runnable {
            if (!playing || session != playbackToken) return@Runnable
            if (attempt != currentAttempt) return@Runnable
            currentAttempt++
            handler.postDelayed({
                if (!playing || session != playbackToken) return@postDelayed
                if (retry < maxStepRetries) dispatchStep(step, index, session, retry + 1)
                else failPlayback("مرحله ${index + 1} ناموفق بود؛ پخش متوقف شد")
            }, 300L)
        }
        watchdogRunnable = r
        handler.postDelayed(r, timeout)
    }

    private fun disarmWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun clickNodeAt(node: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
        if (!hasValidLicense() || node == null) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = try { clickNodeAt(child, x, y) } finally { child.recycle() }
            if (clicked) return true
        }
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (!hasValidLicense()) { current.recycle(); return false }
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { current.recycle(); return true }
            val parent = current.parent; current.recycle(); current = parent
        }
        return false
    }

    private fun performScrollAtPoint(node: AccessibilityNodeInfo?, x: Int, y: Int, forward: Boolean): Boolean {
        if (!hasValidLicense() || node == null) return false
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrolled = try { performScrollAtPoint(child, x, y, forward) } finally { child.recycle() }
            if (scrolled) return true
        }
        if (node.isScrollable) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        return false
    }

    private fun performScrollOnNode(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
        if (!hasValidLicense() || node == null) return false
        if (node.isScrollable) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrolled = try { performScrollOnNode(child, forward) } finally { child.recycle() }
            if (scrolled) return true
        }
        return false
    }

    // ======================= ویرایشگر =======================

    private fun showMacroEditor() {
        val dialog = AlertDialog.Builder(this).create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        val scrollView = ScrollView(this).apply { setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(0xFF1A1D24.toInt()) }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }

        container.addView(TextView(this).apply {
            text = "ویرایش ماکرو (${steps.size} مرحله)"; textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(16))
        })

        steps.forEachIndexed { index, step ->
            val stepView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(0xFF2A2D35.toInt()) }
            val stepText = TextView(this).apply {
                text = "${index + 1}. ${stepTypeName(step.kind)}"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            stepView.addView(stepText)

            val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

            if (index > 0) {
                actionsRow.addView(Button(this).apply {
                    text = "↑"; textSize = 12f; setPadding(dp(6), dp(2), dp(6), dp(2)); minWidth = 0; minHeight = 0
                    setOnClickListener { val temp = steps[index]; steps[index] = steps[index - 1]; steps[index - 1] = temp; saveMacro(); dialog.dismiss(); showMacroEditor() }
                })
            }
            if (index < steps.size - 1) {
                actionsRow.addView(Button(this).apply {
                    text = "↓"; textSize = 12f; setPadding(dp(6), dp(2), dp(6), dp(2)); minWidth = 0; minHeight = 0
                    setOnClickListener { val temp = steps[index]; steps[index] = steps[index + 1]; steps[index + 1] = temp; saveMacro(); dialog.dismiss(); showMacroEditor() }
                })
            }
            actionsRow.addView(Button(this).apply {
                text = "🗑️"; textSize = 12f; setPadding(dp(6), dp(2), dp(6), dp(2)); minWidth = 0; minHeight = 0
                setOnClickListener { steps.removeAt(index); saveMacro(); dialog.dismiss(); showMacroEditor() }
            })

            stepView.addView(actionsRow)
            container.addView(stepView)
        }

        container.addView(Button(this).apply {
            text = "➕ افزودن مرحله جدید"; textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { dialog.dismiss(); showAddStepDialog() }
        })

        container.addView(Button(this).apply {
            text = "بستن"; textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { dialog.dismiss() }
        })
        scrollView.addView(container)
        dialog.setView(scrollView)
        dialog.show()
    }

    private fun showAddStepDialog() {
        val dialog = AlertDialog.Builder(this).create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(0xFF1A1D24.toInt()) }

        layout.addView(TextView(this).apply { text = "نوع مرحله را انتخاب کنید:"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) })

        listOf("کلیک (مرکز صفحه)", "اسکرول به پایین", "دکمه بازگشت").forEach { type ->
            layout.addView(Button(this).apply {
                text = type; textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    val w = resources.displayMetrics.widthPixels.toFloat()
                    val h = resources.displayMetrics.heightPixels.toFloat()
                    when (type) {
                        "کلیک (مرکز صفحه)" -> steps.add(Step(kind = "TAP", x = w / 2f, y = h / 2f, delay = 500L))
                        "اسکرول به پایین" -> steps.add(Step(kind = "SWIPE", x = w / 2f, y = h * 0.7f, x2 = w / 2f, y2 = h * 0.3f, delay = 500L, duration = 300L))
                        "دکمه بازگشت" -> steps.add(Step(kind = "BACK", delay = 500L))
                    }
                    saveMacro()
                    dialog.dismiss()
                    showMacroEditor()
                }
            })
        }
        dialog.setView(layout)
        dialog.show()
    }

    private fun updatePanelSize() {
        if (!hasValidLicense()) { removePanel(); return }
        val params = panelParams ?: return
        params.width = dp(panelWidthDp)
        params.height = dp(panelHeightDp)
        try {
            panel?.let { wm.updateViewLayout(it, params) }
        } catch (_: Exception) { }
        getSharedPreferences("touch_loop", MODE_PRIVATE).edit()
            .putInt("panel_width_dp", panelWidthDp).putInt("panel_height_dp", panelHeightDp).apply()
    }

    // ======================= قفل لمس =======================

    private fun showTouchLock() {
        if (touchLockOverlay != null) return
        isCurrentlyLocked = true
        touchLockOverlay = View(this).apply {
            setBackgroundColor(0x22000000)
            setOnTouchListener { _, _ -> true }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(touchLockOverlay, params)
            bringControlsToFront()
        } catch (_: Exception) { }
    }

    private fun hideTouchLock() {
        isCurrentlyLocked = false
        touchLockOverlay?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        touchLockOverlay = null
    }

    private fun temporarilyUnlockForPlayback() {
        if (isCurrentlyLocked && touchLockOverlay != null) {
            hideTouchLock()
        }
    }

    private fun restoreLockAfterPlayback() {
        if (userWantsTouchLocked && touchLockOverlay == null) {
            showTouchLock()
        }
    }

    // ======================= انتخاب نقطه‌ی کلیک (نوار کوچک) =======================

    private fun showPointPicker() {
        if (!hasValidLicense() || pointPicker != null) return
        val root = FrameLayout(this).apply { setBackgroundColor(0x14000000) }

        // نوار کوچک پایین صفحه؛ دیگر جلوی دکمه‌ی بازگشت بالای صفحه را نمی‌گیرد
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6202124.toInt())
            setPadding(dp(10), dp(2), dp(2), dp(2))
        }
        bar.addView(TextView(this).apply {
            text = "محل کلیک را لمس کن"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
        })
        bar.addView(Button(this).apply {
            text = "✕"
            textSize = 11f
            minWidth = 0; minHeight = 0
            minimumWidth = 0; minimumHeight = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { removePointPicker(); status?.text = "انتخاب لغو شد" }
        })
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(28) })

        root.setOnTouchListener { _, event ->
            if (!hasValidLicense()) { removePointPicker(); return@setOnTouchListener true }
            if (event.action == MotionEvent.ACTION_UP && pointPicker === root) {
                val x = event.rawX; val y = event.rawY
                removePointPicker()
                val now = System.currentTimeMillis()
                val pre = if (lastEventAt == 0L) 350L else (now - lastEventAt).coerceIn(120L, 5000L)
                val step = Step(kind = "TAP", x = x, y = y, delay = pre)
                addRecordedStep(step)
                replayLive(step)
                true
            } else true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        pointPicker = root
        try { wm.addView(root, params); status?.text = "محل کلیک را انتخاب کن" }
        catch (_: Exception) { pointPicker = null; status?.text = "انتخاب نقطه باز نشد" }
    }

    private fun removePointPicker() {
        pointPicker?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        pointPicker = null
    }

    // ======================= سرعت / حباب =======================

    private fun formatSpeed(value: Float): String = String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    private fun updateSpeedLabel() {
        speedLabel?.text = "سرعت ${formatSpeed(playbackSpeed)}×"
        getSharedPreferences("touch_loop", MODE_PRIVATE).edit().putFloat("playback_speed", playbackSpeed).apply()
    }

    private fun collapseToBubble() {
        if (!hasValidLicense() || panel == null || panelCollapsed) return
        val oldParams = panelParams
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        panelCollapsed = true
        val size = dp(52)
        val bubbleView = TextView(this).apply {
            text = "TL"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4258A8.toInt())
            contentDescription = "Touch Loop؛ لمس برای بازکردن پنل"
        }
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = oldParams?.x ?: dp(12); y = oldParams?.y ?: dp(140) }
        bubbleParams = params
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var moved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        bubbleView.setOnTouchListener { view, event ->
            if (!hasValidLicense()) { removePanel(); return@setOnTouchListener true }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        params.x = (initialX + dx).coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) expandFromBubble(); true }
                else -> true
            }
        }
        bubble = bubbleView
        try {
            wm.addView(bubbleView, params)
            updateKeepScreenOnFlag(playing)
        } catch (_: Exception) {
            bubble = null
            panelCollapsed = false
            showPanel()
        }
    }

    private fun expandFromBubble() {
        if (!hasValidLicense()) { removePanel(); return }
        bubble?.let {
            try { wm.removeView(it) } catch (_: Exception) { }
        }
        val oldParams = bubbleParams
        bubble = null
        bubbleParams = null
        panelCollapsed = false
        showPanel()
        val params = panelParams
        if (params != null && oldParams != null) {
            params.x = oldParams.x
            params.y = oldParams.y
            try {
                panel?.let { wm.updateViewLayout(it, params) }
            } catch (_: Exception) { }
        }
    }

    // ======================= توقف / ذخیره / بارگذاری =======================

    private fun stopPlayback() {
        val wasPlaying = playing
        playing = false
        playbackToken++
        currentAttempt++
        playIndex = 0
        loopCount = 0
        expectedPkg = null
        pkgMismatch = 0
        executionReport?.visibility = View.GONE
        disarmWatchdog()
        updateKeepScreenOnFlag(false)
        if (wasPlaying) {
            handler.postDelayed({ restoreLockAfterPlayback() }, 300L)
        }
    }

    private fun stopAll() {
        recording = false
        hideCaptureOverlay()
        removePointPicker()
        stopPlayback()
        saveMacro()
    }

    private fun saveMacro() {
        val rootObj = JSONObject(); rootObj.put("version", 3)
        val array = JSONArray()
        steps.forEach { step ->
            array.put(JSONObject().apply {
                put("kind", step.kind)
                put("x", step.x.toDouble()); put("y", step.y.toDouble())
                put("x2", step.x2.toDouble()); put("y2", step.y2.toDouble())
                put("delay", step.delay); put("duration", step.duration)
            })
        }
        rootObj.put("steps", array)
        getSharedPreferences("touch_loop", MODE_PRIVATE).edit().putString("macro", rootObj.toString()).apply()
    }

    private fun loadMacro() {
        steps.clear()
        val raw = getSharedPreferences("touch_loop", MODE_PRIVATE).getString("macro", null) ?: return
        try {
            val array = if (raw.trim().startsWith("{")) {
                val rootObj = JSONObject(raw); rootObj.optJSONArray("steps") ?: JSONArray()
            } else { JSONArray(raw) }
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val kind = obj.getString("kind")
                if (kind == "SCROLL") {
                    // ماکروی قدیمی: به کشیدن تقریبی تبدیل می‌شود (برای دقت کامل دوباره ضبط کنید)
                    val top = obj.optInt("top", 0)
                    val bottom = obj.optInt("bottom", 0)
                    val delta = obj.optInt("scrollDeltaY", 0)
                    val h = (bottom - top).toFloat().coerceAtLeast(300f)
                    val dist = (abs(delta).toFloat() * 0.8f).coerceIn(h * 0.3f, h * 0.8f)
                    val cx = obj.optDouble("x", 0.0).toFloat()
                    val cy = if (bottom > top) (top + bottom) / 2f else obj.optDouble("y", 0.0).toFloat()
                    val forward = delta >= 0
                    steps.add(Step(
                        kind = "SWIPE", x = cx, y = if (forward) cy + dist / 2f else cy - dist / 2f,
                        x2 = cx, y2 = if (forward) cy - dist / 2f else cy + dist / 2f,
                        delay = obj.optLong("delay", 350L),
                        duration = obj.optLong("duration", 300L).coerceIn(150L, 600L)
                    ))
                } else {
                    steps.add(Step(
                        kind = kind,
                        x = obj.optDouble("x", 0.0).toFloat(),
                        y = obj.optDouble("y", 0.0).toFloat(),
                        x2 = obj.optDouble("x2", 0.0).toFloat(),
                        y2 = obj.optDouble("y2", 0.0).toFloat(),
                        delay = obj.optLong("delay", 350L),
                        duration = obj.optLong("duration", 0L)
                    ))
                }
            }
        } catch (_: Exception) { steps.clear() }
    }

    private fun removePanel() {
        recording = false
        hideCaptureOverlay()
        stopPlayback()
        removePointPicker()
        hideTouchLock()
        panel?.let {
            try { wm.removeView(it) } catch (_: Exception) { }
        }
        bubble?.let {
            try { wm.removeView(it) } catch (_: Exception) { }
        }
        panel = null
        bubble = null
        panelParams = null
        bubbleParams = null
        panelCollapsed = false
        status = null
        speedLabel = null
        executionReport = null
        lockButton = null
    }

    override fun onDestroy() { removePanel(); super.onDestroy() }
}
