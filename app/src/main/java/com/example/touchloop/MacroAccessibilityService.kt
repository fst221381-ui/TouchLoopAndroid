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
    private var activePlaybackSpeed = 1.0f

    private var speedLabel: TextView? = null
    private var status: TextView? = null
    private var executionReport: TextView? = null
    private var pointPicker: View? = null

    private lateinit var wm: WindowManager

    private val minPanelWidthDp = 140
    private val minPanelHeightDp = 180

    private val maxStepRetries = 3
    private var watchdogRunnable: Runnable? = null
    private var currentAttempt = 0L

    private var expectedPkg: String? = null
    private var pkgMismatch = 0
    private var lastUiChangeAt = 0L

    // مکث ثابت پس از مراحل حذف شده است.
    // مرحله بعد پس از دریافت نتیجه اجرای مرحله فعلی شروع می‌شود.
    private val tapSettleMin = 0L
    private val swipeSettleMin = 0L
    private val backSettleMin = 0L
    private val settleQuiet = 0L
    private val settleMax = 0L

    // بدون فاصله اضافی بین دورهای ماکرو
    private val loopGap = 0L

    // ژست دوم برای نگه‌داشتن انتهای اسکرول غیرفعال است.
    private val suppressFling = false
    private val scrollHoldDuration = 0L

    // سرعت فقط مدت حرکت را تغییر می‌دهد، نه مختصات یا مسافت را.
    private val minSwipeDuration = 5L
    private val maxSwipeDuration = 3000L

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

    private val powerManager: PowerManager? by lazy {
        getSystemService(POWER_SERVICE) as? PowerManager
    }

    private val licenseDateFormatter =
        java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss",
            java.util.Locale.US
        ).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

    private val baseUrl =
        "https://rkllnarnvbylpphshweu.supabase.co"

    private val checkLicenseEndpoint =
        "$baseUrl/functions/v1/check-license"

    private val apiKey =
        "sb_publishable_t8cRQM_w5THIwJvgXrYJNg_Fbxoaz6_"

    private var lastOnlineCheck = 0L
    private val checkInterval = 2 * 60 * 1000L

    private fun isScreenOn(): Boolean =
        powerManager?.isInteractive ?: true

    private fun scaledTime(
        original: Long,
        speed: Float = activePlaybackSpeed,
        minimum: Long = 1L
    ): Long {
        if (original <= 0L) return 0L

        return (original / speed.coerceIn(0.25f, 8.0f))
            .toLong()
            .coerceAtLeast(minimum)
    }

    private fun hasValidLicense(): Boolean {
        if (isLicenseBlocked) return false

        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)

        if (!prefs.getBoolean("license_active", false)) {
            return false
        }

        val currentDevice = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: return false

        if (prefs.getString("license_device_id", "") != currentDevice) {
            return false
        }

        val expiryText =
            prefs.getString("license_expires_at", null) ?: return false

        return try {
            val expiry =
                licenseDateFormatter.parse(expiryText.take(19))
                    ?: return false

            val isValid = System.currentTimeMillis() < expiry.time

            if (!isValid) {
                isLicenseBlocked = true
                prefs.edit()
                    .putBoolean("license_active", false)
                    .apply()
            }

            isValid
        } catch (_: Exception) {
            false
        }
    }

    private fun checkLicenseStatusOnline(force: Boolean = false) {
        val now = System.currentTimeMillis()

        if (!force && now - lastOnlineCheck < checkInterval) return

        lastOnlineCheck = now

        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: return

        Thread {
            try {
                val connection =
                    java.net.URL(checkLicenseEndpoint)
                        .openConnection() as java.net.HttpURLConnection

                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.doOutput = true

                    connection.setRequestProperty("apikey", apiKey)
                    connection.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    val body = JSONObject().apply {
                        put("device_id", deviceId)
                    }

                    java.io.OutputStreamWriter(
                        connection.outputStream,
                        Charsets.UTF_8
                    ).use {
                        it.write(body.toString())
                    }

                    val responseCode = connection.responseCode

                    val stream =
                        if (responseCode in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }

                    val responseText =
                        stream?.bufferedReader()?.use { it.readText() } ?: ""

                    val responseJson = JSONObject(responseText)

                    if (responseJson.optBoolean("success", false)) {
                        val isValid =
                            responseJson.optBoolean("is_valid", false)

                        val isBlocked =
                            responseJson.optBoolean("is_blocked", false)

                        val isExpired =
                            responseJson.optBoolean("is_expired", false)

                        if (!isValid || isBlocked || isExpired) {
                            isLicenseBlocked = true

                            getSharedPreferences(
                                "touch_loop",
                                MODE_PRIVATE
                            ).edit()
                                .putBoolean("license_active", false)
                                .apply()

                            handler.post {
                                stopAll()
                                removePanel()
                                hideTouchLock()
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // خطای شبکه به‌تنهایی لایسنس را مسدود نمی‌کند.
            }
        }.start()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        checkLicenseStatusOnline(force = true)

        if (!hasValidLicense()) {
            stopAll()
            return
        }

        loadMacro()

        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)

        playbackSpeed = prefs
            .getFloat("playback_speed", 1.0f)
            .coerceIn(0.25f, 8.0f)

        panelWidthDp = prefs
            .getInt("panel_width_dp", 220)
            .coerceIn(minPanelWidthDp, 450)

        panelHeightDp = prefs
            .getInt("panel_height_dp", 320)
            .coerceIn(minPanelHeightDp, 800)

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

        val eventType = event.eventType

        if (
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            if (event.packageName?.toString() != packageName) {
                lastUiChangeAt = System.currentTimeMillis()
            }
        }
    }

    override fun onInterrupt() {
        stopAll()
    }

    private fun addRecordedStep(step: Step) {
        if (!hasValidLicense()) return

        steps.add(step)
        lastEventAt = System.currentTimeMillis()

        status?.text =
            if (recording) {
                "در حال ضبط • ${steps.size} مرحله"
            } else {
                "${steps.size} مرحله"
            }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun scaleFactor(): Float =
        (panelWidthDp / 220f).coerceIn(0.6f, 1.4f)

    private fun scaledDp(value: Int): Int =
        dp((value * scaleFactor()).toInt().coerceAtLeast(1))

    private fun updateKeepScreenOnFlag(on: Boolean) {
        panelParams?.let { params ->
            val newFlags =
                if (on) {
                    params.flags or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    params.flags and
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                }

            if (newFlags != params.flags) {
                params.flags = newFlags

                panel?.let {
                    try {
                        wm.updateViewLayout(it, params)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        bubbleParams?.let { params ->
            val newFlags =
                if (on) {
                    params.flags or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    params.flags and
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                }

            if (newFlags != params.flags) {
                params.flags = newFlags

                bubble?.let {
                    try {
                        wm.updateViewLayout(it, params)
                    } catch (_: Exception) {
                    }
                }
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
            setPadding(
                scaledDp(8), scaledDp(6),
                scaledDp(8), scaledDp(6)
            )
            setBackgroundColor(0xF221252D.toInt())
        }

        val header = TextView(this).apply {
            text = "☰ TOUCH LOOP"
            textSize = 13f * scale
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(
                scaledDp(8), scaledDp(8),
                scaledDp(8), scaledDp(8)
            )
            setBackgroundColor(0xFF30394A.toInt())
            contentDescription = "برای جابه‌جایی پنل بکشید"
        }

        status = TextView(this).apply {
            text =
                if (isLicenseBlocked) "کد مسدود شده است"
                else "Touch Loop آماده است"

            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f * scale
            gravity = Gravity.CENTER
            setPadding(
                scaledDp(4), scaledDp(6),
                scaledDp(4), scaledDp(6)
            )
        }

        executionReport = TextView(this).apply {
            text = ""
            setTextColor(0xFFB0C4DE.toInt())
            textSize = 10f * scale
            gravity = Gravity.CENTER
            setPadding(
                scaledDp(4), scaledDp(2),
                scaledDp(4), scaledDp(2)
            )
            visibility = View.GONE
        }

        fun button(label: String, action: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 11f * scale
                minimumHeight = scaledDp(32)

                setPadding(
                    scaledDp(4), scaledDp(2),
                    scaledDp(4), scaledDp(2)
                )

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
        }

        var panelDragData: FloatArray? = null

        header.setOnTouchListener { _, event ->
            if (!hasValidLicense()) {
                removePanel()
                return@setOnTouchListener true
            }

            val params = panelParams
                ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    panelDragData = floatArrayOf(
                        event.rawX, event.rawY,
                        params.x.toFloat(), params.y.toFloat()
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val data = panelDragData
                        ?: return@setOnTouchListener true

                    val dx = (event.rawX - data[0]).toInt()
                    val dy = (event.rawY - data[1]).toInt()

                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels

                    params.x = (data[2].toInt() + dx)
                        .coerceIn(
                            0,
                            (screenWidth - params.width).coerceAtLeast(0)
                        )

                    params.y = (data[3].toInt() + dy)
                        .coerceIn(
                            0,
                            (screenHeight - dp(60)).coerceAtLeast(0)
                        )

                    try {
                        wm.updateViewLayout(root, params)
                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    panelDragData = null
                    true
                }

                else -> true
            }
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
        }

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

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

            status?.text = "در حال ضبط • لمس‌ها ثبت می‌شوند"
        })

        contentContainer.addView(button("＋ کلیک") {
            if (!hasValidLicense()) return@button

            if (!recording || playing) {
                status?.text = "ابتدا ضبط را شروع کن"
            } else {
                showPointPicker()
            }
        })

        contentContainer.addView(button("↩ بازگشت") {
            if (!hasValidLicense()) return@button

            if (recording && !playing) {
                addRecordedStep(
                    Step(
                        kind = "BACK",
                        delay = calculateManualDelay()
                    )
                )
                performGlobalAction(GLOBAL_ACTION_BACK)
            } else if (playing) {
                status?.text = "برای توقف، Stop را بزن"
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
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

            if (steps.isEmpty()) {
                status?.text = "ابتدا ماکرو ضبط کن"
            } else {
                playbackToken++
                loopCount = 1
                playing = true
                playIndex = 0

                activePlaybackSpeed = playbackSpeed

                expectedPkg = currentPackage()
                pkgMismatch = 0
                lastUiChangeAt = System.currentTimeMillis()

                status?.text =
                    "در حال پخش با سرعت ${formatSpeed(activePlaybackSpeed)}×"

                updateExecutionReport()
                executionReport?.visibility = View.VISIBLE

                temporarilyUnlockForPlayback()
                updateKeepScreenOnFlag(true)
                runNext(playbackToken)
            }
        })

        contentContainer.addView(button("✎ ویرایش") {
            if (!hasValidLicense()) return@button

            if (playing) {
                status?.text = "ابتدا پخش را متوقف کن"
                return@button
            }

            if (recording) {
                status?.text = "ابتدا ضبط را متوقف کن"
                return@button
            }

            if (steps.isEmpty()) {
                status?.text = "ماکرویی برای ویرایش نیست"
            } else {
                showMacroEditor()
            }
        })

        lockButton = button(
            if (userWantsTouchLocked) "🔓 باز کردن قفل"
            else "🔒 قفل صفحه"
        ) {
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
        }.also {
            contentContainer.addView(it)
        }

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        speedLabel = TextView(this).apply {
            textSize = 11f * scale
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(
                scaledDp(4), scaledDp(4),
                scaledDp(4), scaledDp(4)
            )
        }

        speedRow.addView(
            button("−") {
                playbackSpeed =
                    (playbackSpeed / 2f).coerceAtLeast(0.25f)
                updateSpeedLabel()

                if (playing) {
                    status?.text =
                        "سرعت جدید از اجرای بعدی اعمال می‌شود"
                }
            },
            LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        )

        speedRow.addView(
            speedLabel,
            LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f
            )
        )

        speedRow.addView(
            button("+") {
                playbackSpeed =
                    (playbackSpeed * 2f).coerceAtMost(8.0f)
                updateSpeedLabel()

                if (playing) {
                    status?.text =
                        "سرعت جدید از اجرای بعدی اعمال می‌شود"
                }
            },
            LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        )

        contentContainer.addView(speedRow)

        contentContainer.addView(button("◉ حباب") {
            collapseToBubble()
        })

        contentContainer.addView(button("× بستن") {
            removePanel()
        })

        scrollView.addView(contentContainer)
        root.addView(header)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val resizeHandle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 14f * scale
            setTextColor(0xFF3FBED2.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0x00000000)
            setPadding(
                scaledDp(6), scaledDp(4),
                scaledDp(6), scaledDp(4)
            )
            contentDescription = "برای تغییر اندازه پنل بکشید"
        }

        var resizeData: FloatArray? = null

        resizeHandle.setOnTouchListener { _, event ->
            if (!hasValidLicense()) {
                return@setOnTouchListener true
            }

            if (panelParams == null) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeData = floatArrayOf(
                        event.rawX,
                        event.rawY,
                        panelWidthDp.toFloat(),
                        panelHeightDp.toFloat()
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val data = resizeData
                        ?: return@setOnTouchListener true

                    val dx = (
                        (event.rawX - data[0]) /
                            resources.displayMetrics.density
                    ).toInt()

                    val dy = (
                        (event.rawY - data[1]) /
                            resources.displayMetrics.density
                    ).toInt()

                    panelWidthDp =
                        (data[2].toInt() + dx)
                            .coerceIn(minPanelWidthDp, 450)

                    panelHeightDp =
                        (data[3].toInt() + dy)
                            .coerceIn(minPanelHeightDp, 800)

                    updatePanelSize()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    resizeData = null
                    true
                }

                else -> true
            }
        }

        val handleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        handleRow.addView(resizeHandle)
        root.addView(handleRow)

        val params = WindowManager.LayoutParams(
            dp(panelWidthDp),
            dp(panelHeightDp),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }

        panel = root
        panelParams = params

        try {
            wm.addView(root, params)
            updateSpeedLabel()
            updateKeepScreenOnFlag(playing)
        } catch (_: Exception) {
            panel = null
            panelParams = null
        }
    }

    private fun calculateManualDelay(): Long {
        val now = System.currentTimeMillis()
        return if (lastEventAt == 0L) {
            350L
        } else {
            (now - lastEventAt).coerceIn(0L, 5000L)
        }
    }

    private fun updateExecutionReport() {
        if (!playing || steps.isEmpty()) {
            executionReport?.visibility = View.GONE
            return
        }

        val current = playIndex + 1
        val total = steps.size

        executionReport?.text =
            "دور $loopCount • مرحله $current از $total " +
                "(${stepTypeName(steps.getOrNull(playIndex)?.kind)})"
    }

    private fun stepTypeName(kind: String?): String =
        when (kind) {
            "TAP" -> "کلیک"
            "SWIPE" -> "اسکرول"
            "BACK" -> "بازگشت"
            else -> "نامشخص"
        }

    private fun bringControlsToFront() {
        try {
            val currentPanel = panel
            val currentPanelParams = panelParams

            if (currentPanel != null && currentPanelParams != null) {
                wm.removeView(currentPanel)
                wm.addView(currentPanel, currentPanelParams)
            }

            val currentBubble = bubble
            val currentBubbleParams = bubbleParams

            if (currentBubble != null && currentBubbleParams != null) {
                wm.removeView(currentBubble)
                wm.addView(currentBubble, currentBubbleParams)
            }
        } catch (_: Exception) {
        }
    }

    // ======================= ضبط لمس =======================

    private fun showCaptureOverlay() {
        if (captureOverlay != null) return

        val view = View(this).apply {
            setBackgroundColor(0x00000000)

            setOnTouchListener { _, event ->
                handleCaptureTouch(event)
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(view, params)
            captureOverlay = view
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
        captureOverlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        captureOverlay = null
        captureParams = null
    }

    private fun setCaptureTouchable(touchable: Boolean) {
        val view = captureOverlay ?: return
        val params = captureParams ?: return

        params.flags =
            if (touchable) {
                params.flags and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun handleCaptureTouch(event: MotionEvent) {
        if (!hasValidLicense()) {
            removePanel()
            return
        }

        if (!recording || playing) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                capDownX = event.rawX
                capDownY = event.rawY
                capLastX = event.rawX
                capLastY = event.rawY
                capDownT = event.eventTime
                capMaxDist = 0f
                capMulti = false

                capPreDelay = calculateManualDelay()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                capMulti = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!capMulti) {
                    capLastX = event.rawX
                    capLastY = event.rawY

                    capMaxDist = max(
                        capMaxDist,
                        hypot(
                            capLastX - capDownX,
                            capLastY - capDownY
                        )
                    )
                }
            }

            MotionEvent.ACTION_UP -> {
                if (capMulti) return

                capLastX = event.rawX
                capLastY = event.rawY

                capMaxDist = max(
                    capMaxDist,
                    hypot(
                        capLastX - capDownX,
                        capLastY - capDownY
                    )
                )

                val tapSlop =
                    ViewConfiguration.get(this).scaledTouchSlop * 2f

                val step =
                    if (capMaxDist <= tapSlop) {
                        Step(
                            kind = "TAP",
                            x = capDownX,
                            y = capDownY,
                            delay = capPreDelay
                        )
                    } else {
                        Step(
                            kind = "SWIPE",
                            x = capDownX,
                            y = capDownY,
                            x2 = capLastX,
                            y2 = capLastY,
                            delay = capPreDelay,
                            duration = (
                                event.eventTime - capDownT
                            ).coerceIn(80L, 3000L)
                        )
                    }

                addRecordedStep(step)
                replayLive(step)
            }
        }
    }

    private fun replayLive(step: Step) {
        setCaptureTouchable(false)

        handler.post {
            if (!hasValidLicense() || !recording) {
                setCaptureTouchable(true)
                return@post
            }

            performStep(step, liveCapture = true) {
                handler.post {
                    if (captureOverlay != null && recording) {
                        setCaptureTouchable(true)
                    }

                    // زمان آخرین رویداد هنگام ثبت مرحله تنظیم می‌شود.
                    // اینجا آن را تغییر نمی‌دهیم تا تأخیر مصنوعی ایجاد نشود.
                }
            }
        }
    }

    // ======================= اجرای لمس =======================

    private fun performStep(
        step: Step,
        liveCapture: Boolean = false,
        done: (Boolean) -> Unit
    ) {
        if (!hasValidLicense()) {
            done(false)
            return
        }

        if (Build.VERSION.SDK_INT >= 24) {
            performTouchStep(step, liveCapture, done)
            return
        }

        // اندروید ۶: استفاده از گره‌های دسترس‌پذیری
        val root = rootInActiveWindow

        try {
            if (step.kind == "SWIPE") {
                val forward = step.y2 < step.y

                val ok =
                    performScrollAtPoint(
                        root,
                        step.x.toInt(),
                        step.y.toInt(),
                        forward
                    ) || performScrollOnNode(root, forward)

                if (!ok) {
                    status?.text = "این صفحه اسکرول قابل‌دسترسی ندارد"
                }

                done(ok)
            } else if (step.kind == "BACK") {
                done(performGlobalAction(GLOBAL_ACTION_BACK))
            } else {
                val ok = clickNodeAt(
                    root,
                    step.x.toInt(),
                    step.y.toInt()
                )

                if (!ok) {
                    status?.text = "این کلیک در اندروید ۶ قابل اجرا نیست"
                }

                done(ok)
            }
        } finally {
            root?.recycle()
        }
    }

    @android.annotation.TargetApi(24)
    private fun performTouchStep(
        step: Step,
        liveCapture: Boolean,
        done: (Boolean) -> Unit
    ) {
        var finished = false

        fun finishOnce(success: Boolean) {
            if (finished) return
            finished = true
            done(success)
        }

        val isSwipe = step.kind == "SWIPE"
        val path = Path()

        val duration: Long

        if (isSwipe) {
            // مختصات ثابت هستند؛ سرعت فقط مدت حرکت را تغییر می‌دهد.
            path.moveTo(step.x, step.y)
            path.lineTo(step.x2, step.y2)

            val recordedDuration =
                step.duration.coerceIn(80L, maxSwipeDuration)

            duration = if (liveCapture) {
                recordedDuration
            } else {
                scaledTime(
                    recordedDuration,
                    activePlaybackSpeed,
                    minSwipeDuration
                ).coerceAtMost(maxSwipeDuration)
            }
        } else {
            path.moveTo(step.x, step.y)

            duration = if (liveCapture) {
                35L
            } else {
                scaledTime(
                    35L,
                    activePlaybackSpeed,
                    8L
                )
            }
        }

        // ژست دوم برای نگه‌داشتن انتهای اسکرول حذف شده است.
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    duration
                )
            )
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    finishOnce(true)
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    finishOnce(false)
                }
            },
            null
        )

        if (!accepted) finishOnce(false)
    }

    // ======================= پخش ترتیبی =======================

    private fun currentPackage(): String? {
        val root = rootInActiveWindow ?: return null

        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    private fun failPlayback(message: String) {
        stopPlayback()
        status?.text = message
    }

    /*
     * تابع قبلی waitForSettle عمداً از مسیر پخش حذف شده است.
     * انتظار برای تغییر رابط کاربری و مکث ثابت بعد از هر مرحله
     * باعث کندی محسوس در سرعت‌های بالا می‌شد.
     */

    private fun afterAction(
        kind: String,
        index: Int,
        session: Int
    ) {
        if (!playing || session != playbackToken) return

        if (!hasValidLicense()) {
            stopPlayback()
            return
        }

        // مرحله بعد بلافاصله بعد از callback مرحله جاری شروع می‌شود.
        runStep(index + 1, session, 0)
    }

    private fun runNext(session: Int) {
        runStep(playIndex, session, 0)
    }

    private fun runStep(
        index: Int,
        session: Int,
        retry: Int
    ) {
        if (!hasValidLicense()) {
            stopPlayback()
            restoreLockAfterPlayback()
            status?.text = "کد مسدود یا منقضی شده است"
            removePanel()
            return
        }

        if (!playing || session != playbackToken || steps.isEmpty()) {
            return
        }

        if (index >= steps.size) {
            loopCount++
            playIndex = 0
            updateExecutionReport()

            handler.postDelayed({
                if (
                    hasValidLicense() &&
                    playing &&
                    session == playbackToken
                ) {
                    runStep(0, session, 0)
                }
            }, loopGap)

            return
        }

        playIndex = index
        updateExecutionReport()

        val step = steps[index]

        // زمان انتظار ضبط‌شده با ضریب سرعت تقسیم می‌شود.
        val delay = scaledTime(
            step.delay.coerceAtLeast(0L),
            activePlaybackSpeed,
            0L
        )

        handler.postDelayed({
            if (!hasValidLicense()) {
                stopPlayback()
                return@postDelayed
            }

            if (!playing || session != playbackToken) {
                return@postDelayed
            }

            if (!isScreenOn()) {
                status?.text = "صفحه خاموش است؛ منتظر روشن‌شدن…"

                handler.postDelayed({
                    if (playing && session == playbackToken) {
                        runStep(index, session, retry)
                    }
                }, 1000L)

                return@postDelayed
            }

            val expected = expectedPkg

            if (expected != null) {
                val current = currentPackage()

                if (current != null && current != expected) {
                    pkgMismatch++

                    if (pkgMismatch > 6) {
                        failPlayback("از برنامه خارج شد؛ پخش متوقف شد")
                        return@postDelayed
                    }

                    status?.text = "منتظر بازگشت به برنامه…"

                    handler.postDelayed({
                        if (playing && session == playbackToken) {
                            runStep(index, session, retry)
                        }
                    }, 400L)

                    return@postDelayed
                } else {
                    pkgMismatch = 0
                }
            }

            when (step.kind) {
                "BACK" -> {
                    val accepted =
                        performGlobalAction(GLOBAL_ACTION_BACK)

                    if (accepted) {
                        afterAction("BACK", index, session)
                    } else if (retry < maxStepRetries) {
                        handler.postDelayed({
                            if (playing && session == playbackToken) {
                                runStep(index, session, retry + 1)
                            }
                        }, scaledTime(100L, activePlaybackSpeed, 10L))
                    } else {
                        failPlayback("اجرای مرحله بازگشت ناموفق بود")
                    }
                }

                "TAP", "SWIPE" -> {
                    dispatchStep(step, index, session, retry)
                }

                else -> {
                    runStep(index + 1, session, 0)
                }
            }
        }, delay)
    }

    private fun dispatchStep(
        step: Step,
        index: Int,
        session: Int,
        retry: Int
    ) {
        if (!hasValidLicense()) {
            stopPlayback()
            return
        }

        if (!playing || session != playbackToken) return

        currentAttempt++
        val attempt = currentAttempt

        armWatchdog(
            step,
            index,
            session,
            retry,
            attempt,
            15000L + step.duration
        )

        performStep(step) { ok ->
            if (attempt != currentAttempt) return@performStep

            currentAttempt++
            disarmWatchdog()

            if (!playing || session != playbackToken) {
                return@performStep
            }

            if (ok) {
                afterAction(step.kind, index, session)
            } else if (retry < maxStepRetries) {
                val backoff = scaledTime(
                    if (retry < 2) 120L else 300L,
                    activePlaybackSpeed,
                    20L
                )

                status?.text = "تلاش مجدد مرحله ${index + 1}"

                handler.postDelayed({
                    if (
                        hasValidLicense() &&
                        playing &&
                        session == playbackToken
                    ) {
                        dispatchStep(
                            step,
                            index,
                            session,
                            retry + 1
                        )
                    }
                }, backoff)
            } else {
                failPlayback(
                    "مرحله ${index + 1} ناموفق بود؛ پخش متوقف شد"
                )
            }
        }
    }

    private fun armWatchdog(
        step: Step,
        index: Int,
        session: Int,
        retry: Int,
        attempt: Long,
        timeout: Long
    ) {
        disarmWatchdog()

        val runnable = Runnable {
            if (!playing || session != playbackToken) {
                return@Runnable
            }

            if (attempt != currentAttempt) return@Runnable

            // نتیجه دیررس ژست قبلی دیگر پذیرفته نمی‌شود.
            currentAttempt++
            disarmWatchdog()

            if (retry < maxStepRetries) {
                handler.postDelayed({
                    if (!playing || session != playbackToken) {
                        return@postDelayed
                    }

                    dispatchStep(
                        step,
                        index,
                        session,
                        retry + 1
                    )
                }, scaledTime(100L, activePlaybackSpeed, 20L))
            } else {
                failPlayback(
                    "مرحله ${index + 1} ناموفق بود؛ پخش متوقف شد"
                )
            }
        }

        watchdogRunnable = runnable
        handler.postDelayed(runnable, timeout)
    }

    private fun disarmWatchdog() {
        watchdogRunnable?.let {
            handler.removeCallbacks(it)
        }

        watchdogRunnable = null
    }

    private fun clickNodeAt(
        node: AccessibilityNodeInfo?,
        x: Int,
        y: Int
    ): Boolean {
        if (!hasValidLicense() || node == null) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (!bounds.contains(x, y)) return false

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            val clicked = try {
                clickNodeAt(child, x, y)
            } finally {
                child.recycle()
            }

            if (clicked) return true
        }

        var current: AccessibilityNodeInfo? =
            AccessibilityNodeInfo.obtain(node)

        while (current != null) {
            if (!hasValidLicense()) {
                current.recycle()
                return false
            }

            if (
                current.isClickable &&
                current.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            ) {
                current.recycle()
                return true
            }

            val parent = current.parent
            current.recycle()
            current = parent
        }

        return false
    }

    private fun performScrollAtPoint(
        node: AccessibilityNodeInfo?,
        x: Int,
        y: Int,
        forward: Boolean
    ): Boolean {
        if (!hasValidLicense() || node == null) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (!bounds.contains(x, y)) return false

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            val scrolled = try {
                performScrollAtPoint(child, x, y, forward)
            } finally {
                child.recycle()
            }

            if (scrolled) return true
        }

        if (node.isScrollable) {
            val action =
                if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }

            if (node.performAction(action)) return true
        }

        return false
    }

    private fun performScrollOnNode(
        node: AccessibilityNodeInfo?,
        forward: Boolean
    ): Boolean {
        if (!hasValidLicense() || node == null) return false

        if (node.isScrollable) {
            val action =
                if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }

            if (node.performAction(action)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            val scrolled = try {
                performScrollOnNode(child, forward)
            } finally {
                child.recycle()
            }

            if (scrolled) return true
        }

        return false
    }

    // ======================= ویرایشگر =======================

    private fun showMacroEditor() {
        val dialog = AlertDialog.Builder(this).create()

        dialog.window?.setType(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )

        val scrollView = ScrollView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFF1A1D24.toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        container.addView(TextView(this).apply {
            text = "ویرایش ماکرو (${steps.size} مرحله)"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(16))
        })

        steps.forEachIndexed { index, step ->
            val stepView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(0xFF2A2D35.toInt())
            }

            val stepText = TextView(this).apply {
                text = "${index + 1}. ${stepTypeName(step.kind)}"
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            stepView.addView(stepText)

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            if (index > 0) {
                actionsRow.addView(Button(this).apply {
                    text = "↑"
                    textSize = 12f
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    minWidth = 0
                    minHeight = 0

                    setOnClickListener {
                        val temp = steps[index]
                        steps[index] = steps[index - 1]
                        steps[index - 1] = temp

                        saveMacro()
                        dialog.dismiss()
                        showMacroEditor()
                    }
                })
            }

            if (index < steps.size - 1) {
                actionsRow.addView(Button(this).apply {
                    text = "↓"
                    textSize = 12f
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    minWidth = 0
                    minHeight = 0

                    setOnClickListener {
                        val temp = steps[index]
                        steps[index] = steps[index + 1]
                        steps[index + 1] = temp

                        saveMacro()
                        dialog.dismiss()
                        showMacroEditor()
                    }
                })
            }

            actionsRow.addView(Button(this).apply {
                text = "🗑️"
                textSize = 12f
                setPadding(dp(6), dp(2), dp(6), dp(2))
                minWidth = 0
                minHeight = 0

                setOnClickListener {
                    steps.removeAt(index)
                    saveMacro()
                    dialog.dismiss()
                    showMacroEditor()
                }
            })

            stepView.addView(actionsRow)
            container.addView(stepView)
        }

        container.addView(Button(this).apply {
            text = "➕ افزودن مرحله جدید"
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))

            setOnClickListener {
                dialog.dismiss()
                showAddStepDialog()
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

    private fun showAddStepDialog() {
        val dialog = AlertDialog.Builder(this).create()

        dialog.window?.setType(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFF1A1D24.toInt())
        }

        layout.addView(TextView(this).apply {
            text = "نوع مرحله را انتخاب کنید:"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })

        listOf(
            "کلیک (مرکز صفحه)",
            "اسکرول به پایین",
            "دکمه بازگشت"
        ).forEach { type ->
            layout.addView(Button(this).apply {
                text = type
                textSize = 14f
                setPadding(dp(16), dp(12), dp(16), dp(12))

                setOnClickListener {
                    val width =
                        resources.displayMetrics.widthPixels.toFloat()

                    val height =
                        resources.displayMetrics.heightPixels.toFloat()

                    when (type) {
                        "کلیک (مرکز صفحه)" -> {
                            steps.add(
                                Step(
                                    kind = "TAP",
                                    x = width / 2f,
                                    y = height / 2f,
                                    delay = 500L
                                )
                            )
                        }

                        "اسکرول به پایین" -> {
                            steps.add(
                                Step(
                                    kind = "SWIPE",
                                    x = width / 2f,
                                    y = height * 0.7f,
                                    x2 = width / 2f,
                                    y2 = height * 0.3f,
                                    delay = 500L,
                                    duration = 300L
                                )
                            )
                        }

                        "دکمه بازگشت" -> {
                            steps.add(
                                Step(
                                    kind = "BACK",
                                    delay = 500L
                                )
                            )
                        }
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
        if (!hasValidLicense()) {
            removePanel()
            return
        }

        val params = panelParams ?: return

        params.width = dp(panelWidthDp)
        params.height = dp(panelHeightDp)

        try {
            panel?.let {
                wm.updateViewLayout(it, params)
            }
        } catch (_: Exception) {
        }

        getSharedPreferences("touch_loop", MODE_PRIVATE)
            .edit()
            .putInt("panel_width_dp", panelWidthDp)
            .putInt("panel_height_dp", panelHeightDp)
            .apply()
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
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(touchLockOverlay, params)
            bringControlsToFront()
        } catch (_: Exception) {
        }
    }

    private fun hideTouchLock() {
        isCurrentlyLocked = false

        touchLockOverlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

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

    // ======================= انتخاب نقطه کلیک =======================

    private fun showPointPicker() {
        if (!hasValidLicense() || pointPicker != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x14000000)
        }

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
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))

            setOnClickListener {
                removePointPicker()
                status?.text = "انتخاب لغو شد"
            }
        })

        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(28)
            }
        )

        root.setOnTouchListener { _, event ->
            if (!hasValidLicense()) {
                removePointPicker()
                return@setOnTouchListener true
            }

            if (
                event.action == MotionEvent.ACTION_UP &&
                pointPicker === root
            ) {
                val x = event.rawX
                val y = event.rawY

                removePointPicker()

                val step = Step(
                    kind = "TAP",
                    x = x,
                    y = y,
                    delay = calculateManualDelay()
                )

                addRecordedStep(step)
                replayLive(step)

                true
            } else {
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        pointPicker = root

        try {
            wm.addView(root, params)
            status?.text = "محل کلیک را انتخاب کن"
        } catch (_: Exception) {
            pointPicker = null
            status?.text = "انتخاب نقطه باز نشد"
        }
    }

    private fun removePointPicker() {
        pointPicker?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        pointPicker = null
    }

    // ======================= سرعت و حباب =======================

    private fun formatSpeed(value: Float): String =
        String.format(
            java.util.Locale.US,
            "%.2f",
            value
        ).trimEnd('0').trimEnd('.')

    private fun updateSpeedLabel() {
        speedLabel?.text = "سرعت ${formatSpeed(playbackSpeed)}×"

        getSharedPreferences("touch_loop", MODE_PRIVATE)
            .edit()
            .putFloat("playback_speed", playbackSpeed)
            .apply()
    }

    private fun collapseToBubble() {
        if (!hasValidLicense() || panel == null || panelCollapsed) return

        val oldParams = panelParams

        panel?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

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
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = oldParams?.x ?: dp(12)
            y = oldParams?.y ?: dp(140)
        }

        bubbleParams = params

        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var moved = false

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        bubbleView.setOnTouchListener { view, event ->
            if (!hasValidLicense()) {
                removePanel()
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    if (
                        abs(dx) > touchSlop ||
                        abs(dy) > touchSlop
                    ) {
                        moved = true
                    }

                    if (moved) {
                        params.x = (initialX + dx).coerceIn(
                            0,
                            (
                                resources.displayMetrics.widthPixels - size
                            ).coerceAtLeast(0)
                        )

                        params.y = (initialY + dy).coerceIn(
                            0,
                            (
                                resources.displayMetrics.heightPixels - size
                            ).coerceAtLeast(0)
                        )

                        try {
                            wm.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) expandFromBubble()
                    true
                }

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
        if (!hasValidLicense()) {
            removePanel()
            return
        }

        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
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
                panel?.let {
                    wm.updateViewLayout(it, params)
                }
            } catch (_: Exception) {
            }
        }
    }

    // ======================= توقف، ذخیره و بارگذاری =======================

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
            handler.postDelayed({
                restoreLockAfterPlayback()
            }, 300L)
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
        val rootObj = JSONObject()
        rootObj.put("version", 3)

        val array = JSONArray()

        steps.forEach { step ->
            array.put(
                JSONObject().apply {
                    put("kind", step.kind)
                    put("x", step.x.toDouble())
                    put("y", step.y.toDouble())
                    put("x2", step.x2.toDouble())
                    put("y2", step.y2.toDouble())
                    put("delay", step.delay)
                    put("duration", step.duration)
                }
            )
        }

        rootObj.put("steps", array)

        getSharedPreferences("touch_loop", MODE_PRIVATE)
            .edit()
            .putString("macro", rootObj.toString())
            .apply()
    }

    private fun loadMacro() {
        steps.clear()

        val raw = getSharedPreferences(
            "touch_loop",
            MODE_PRIVATE
        ).getString("macro", null) ?: return

        try {
            val array =
                if (raw.trim().startsWith("{")) {
                    val rootObj = JSONObject(raw)
                    rootObj.optJSONArray("steps") ?: JSONArray()
                } else {
                    JSONArray(raw)
                }

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val kind = obj.getString("kind")

                if (kind == "SCROLL") {
                    val top = obj.optInt("top", 0)
                    val bottom = obj.optInt("bottom", 0)
                    val delta = obj.optInt("scrollDeltaY", 0)

                    val height =
                        (bottom - top).toFloat().coerceAtLeast(300f)

                    val distance = (
                        abs(delta).toFloat() * 0.8f
                    ).coerceIn(height * 0.3f, height * 0.8f)

                    val centerX =
                        obj.optDouble("x", 0.0).toFloat()

                    val centerY =
                        if (bottom > top) {
                            (top + bottom) / 2f
                        } else {
                            obj.optDouble("y", 0.0).toFloat()
                        }

                    val forward = delta >= 0

                    steps.add(
                        Step(
                            kind = "SWIPE",
                            x = centerX,
                            y = if (forward) {
                                centerY + distance / 2f
                            } else {
                                centerY - distance / 2f
                            },
                            x2 = centerX,
                            y2 = if (forward) {
                                centerY - distance / 2f
                            } else {
                                centerY + distance / 2f
                            },
                            delay = obj.optLong("delay", 350L),
                            duration = obj.optLong(
                                "duration",
                                300L
                            ).coerceIn(80L, 3000L)
                        )
                    )
                } else {
                    steps.add(
                        Step(
                            kind = kind,
                            x = obj.optDouble("x", 0.0).toFloat(),
                            y = obj.optDouble("y", 0.0).toFloat(),
                            x2 = obj.optDouble("x2", 0.0).toFloat(),
                            y2 = obj.optDouble("y2", 0.0).toFloat(),
                            delay = obj.optLong("delay", 350L)
                                .coerceIn(0L, 5000L),
                            duration = obj.optLong("duration", 0L)
                                .coerceIn(0L, 3000L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            steps.clear()
        }
    }

    private fun removePanel() {
        recording = false

        hideCaptureOverlay()
        stopPlayback()
        removePointPicker()
        hideTouchLock()

        panel?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
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

    override fun onDestroy() {
        removePanel()
        super.onDestroy()
    }
}
