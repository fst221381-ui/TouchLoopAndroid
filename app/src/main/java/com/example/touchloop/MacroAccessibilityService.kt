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
import kotlin.math.max
import kotlin.math.min

class MacroAccessibilityService : AccessibilityService() {

    data class Step(
        val kind: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
        val delay: Long = 350L,
        val duration: Long = 0L,
        val scrollDeltaY: Int = 0
    )

    private val handler = Handler(Looper.getMainLooper())
    private val steps = mutableListOf<Step>()

    private var recording = false
    private var playing = false
    private var playbackToken = 0
    private var playIndex = 0
    private var loopCount = 0 // شمارنده تکرار ماکرو
    private var lastEventAt = 0L

    private var panel: View? = null
    private var bubble: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelCollapsed = false
    private var touchBlocker: View? = null // لایه مسدودکننده لمس

    private var panelWidthDp = 220
    private var panelHeightDp = 320

    private var playbackSpeed = 1.0f
    private var speedLabel: TextView? = null
    private var status: TextView? = null
    private var executionReport: TextView? = null
    private var pointPicker: View? = null
    private var ignoreEventsUntil = 0L

    private lateinit var wm: WindowManager
    private var pendingScroll: Step? = null
    private var scrollStartTime: Long = 0L

    private val flushScrollRunnable = Runnable { flushPendingScroll() }
    private val minPanelWidthDp = 140
    private val minPanelHeightDp = 180

    private fun hasValidLicense(): Boolean {
        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)
        if (!prefs.getBoolean("license_active", false)) return false
        val currentDevice = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: return false
        if (prefs.getString("license_device_id", "") != currentDevice) return false
        val expiryText = prefs.getString("license_expires_at", null) ?: return false
        return try {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val expiry = formatter.parse(expiryText.take(19)) ?: return false
            System.currentTimeMillis() < expiry.time
        } catch (_: Exception) { false }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!hasValidLicense()) { stopAll(); return }
        loadMacro()
        val prefs = getSharedPreferences("touch_loop", MODE_PRIVATE)
        playbackSpeed = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.25f, 8.0f)
        panelWidthDp = prefs.getInt("panel_width_dp", 220).coerceIn(minPanelWidthDp, 450)
        panelHeightDp = prefs.getInt("panel_height_dp", 320).coerceIn(minPanelHeightDp, 800)
        showPanel()
        updateSpeedLabel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!hasValidLicense()) { recording = false; stopPlayback(); return }
        if (event == null || !recording || playing) return
        if (System.currentTimeMillis() < ignoreEventsUntil) return
        if (event.packageName?.toString() == packageName) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED && event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val source = event.source ?: return
        val bounds = Rect()
        try { source.getBoundsInScreen(bounds) } finally { source.recycle() }
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val now = System.currentTimeMillis()
        val delay = if (lastEventAt == 0L) 350L else (now - lastEventAt).coerceIn(120L, 5000L)

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pendingScroll == null) scrollStartTime = now
            val delta = if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else 0
            val newScroll = Step(kind = "SCROLL", x = bounds.exactCenterX(), y = bounds.exactCenterY(),
                left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom, delay = delay, scrollDeltaY = delta)

            val oldScroll = pendingScroll
            if (oldScroll != null) {
                val sameRegion = abs(oldScroll.x - newScroll.x) < 80f && abs(oldScroll.y - newScroll.y) < 100f &&
                        oldScroll.left == newScroll.left && oldScroll.right == newScroll.right && oldScroll.top == newScroll.top && oldScroll.bottom == newScroll.bottom
                val sameDirection = oldScroll.scrollDeltaY == 0 || newScroll.scrollDeltaY == 0 || oldScroll.scrollDeltaY.signCompat() == newScroll.scrollDeltaY.signCompat()

                if (sameRegion && sameDirection) {
                    val accumulated = (oldScroll.scrollDeltaY.toLong() + newScroll.scrollDeltaY.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    pendingScroll = newScroll.copy(scrollDeltaY = accumulated, delay = oldScroll.delay)
                } else { flushPendingScroll(); pendingScroll = newScroll }
            } else { pendingScroll = newScroll }
            handler.removeCallbacks(flushScrollRunnable)
            handler.postDelayed(flushScrollRunnable, 200L)
        } else {
            handler.removeCallbacks(flushScrollRunnable)
            flushPendingScroll()
            addRecordedStep(Step(kind = "TAP", x = bounds.exactCenterX(), y = bounds.exactCenterY(),
                left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom, delay = delay))
        }
    }

    private fun Int.signCompat(): Int = when { this > 0 -> 1; this < 0 -> -1; else -> 0 }

    private fun flushPendingScroll() {
        val scroll = pendingScroll ?: return
        pendingScroll = null
        if (recording && !playing && hasValidLicense()) {
            val duration = (System.currentTimeMillis() - scrollStartTime).coerceIn(100L, 3000L)
            addRecordedStep(scroll.copy(duration = duration))
        }
    }

    private fun addRecordedStep(step: Step) {
        if (!hasValidLicense()) return
        steps.add(step)
        lastEventAt = System.currentTimeMillis()
        status?.text = if (recording) "در حال ضبط • ${steps.size} مرحله" else "${steps.size} مرحله"
    }

    override fun onInterrupt() { stopAll() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun scaleFactor(): Float = (panelWidthDp / 220f).coerceIn(0.6f, 1.4f)
    private fun scaledDp(value: Int): Int = dp((value * scaleFactor()).toInt().coerceAtLeast(1))

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
            text = "Touch Loop آماده است"
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
                if (!hasValidLicense()) { recording = false; stopPlayback(); status?.text = "مجوز فعال نیست"; removePanel() }
                else { action() }
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
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
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
            stopPlayback(); handler.removeCallbacks(flushScrollRunnable); pendingScroll = null; steps.clear()
            recording = true; lastEventAt = 0L; status?.text = "در حال ضبط…"; executionReport?.visibility = View.GONE
        })
        contentContainer.addView(button("＋ کلیک") {
            if (!recording || playing) status?.text = "ابتدا ضبط را شروع کن" else showPointPicker()
        })
        contentContainer.addView(button("↩ بازگشت") {
            if (recording && !playing) {
                handler.removeCallbacks(flushScrollRunnable); flushPendingScroll(); addRecordedStep(Step(kind = "BACK")); performGlobalAction(GLOBAL_ACTION_BACK)
            } else if (playing) { status?.text = "برای توقف، Stop را بزن" }
            else { performGlobalAction(GLOBAL_ACTION_BACK) }
        })
        contentContainer.addView(button("■ توقف") {
            if (recording) { handler.removeCallbacks(flushScrollRunnable); flushPendingScroll() }
            recording = false; stopPlayback(); saveMacro(); status?.text = "ذخیره شد • ${steps.size} مرحله"; executionReport?.visibility = View.GONE
        })
        contentContainer.addView(button("▶ پخش") {
            if (recording) { handler.removeCallbacks(flushScrollRunnable); flushPendingScroll() }
            recording = false; stopPlayback(); saveMacro()
            if (steps.isEmpty()) { status?.text = "ابتدا ماکرو ضبط کن" }
            else { playbackToken++; loopCount = 1; playing = true; playIndex = 0; status?.text = "در حال پخش…"; updateExecutionReport(); executionReport?.visibility = View.VISIBLE; showTouchBlocker(); runNext(playbackToken) }
        })
        contentContainer.addView(button("✎ ویرایش") {
            if (steps.isEmpty()) status?.text = "ماکرویی برای ویرایش نیست" else showMacroEditor()
        })

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

        // ✅ دستگیره تغییر اندازه اصلاح‌شده (بدون بازسازی پنل)
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
            val params = panelParams ?: return@setOnTouchListener false
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
                    updatePanelSize() // فقط ابعاد را آپدیت می‌کند، پنل را بازسازی نمی‌کند
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
        try { wm.addView(root, params); updateSpeedLabel() } catch (_: Exception) { panel = null; panelParams = null }
    }

    private fun updateExecutionReport() {
        if (!playing || steps.isEmpty()) { executionReport?.visibility = View.GONE; return }
        val current = playIndex + 1
        val total = steps.size
        val remaining = total - current
        val step = steps.getOrNull(playIndex)
        val stepType = when (step?.kind) { "TAP" -> "کلیک"; "SCROLL" -> "اسکرول"; "BACK" -> "بازگشت"; else -> "نامشخص" }
        executionReport?.text = "دور $loopCount • مرحله $current از $total ($stepType)"
    }

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
                val type = when (step.kind) { "TAP" -> "کلیک"; "SCROLL" -> "اسکرول"; "BACK" -> "بازگشت"; else -> "نامشخص" }
                text = "${index + 1}. $type"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            stepView.addView(stepText)

            // ✅ دکمه‌های کوچک و کنار هم
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

        // ✅ دکمه افزودن مرحله جدید
        container.addView(Button(this).apply {
            text = "➕ افزودن مرحله جدید"; textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { 
                dialog.dismiss()
                showAddStepDialog()
            }
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
                    when (type) {
                        "کلیک (مرکز صفحه)" -> steps.add(Step(kind = "TAP", x = (resources.displayMetrics.widthPixels / 2).toFloat(), y = (resources.displayMetrics.heightPixels / 2).toFloat(), delay = 500L))
                        "اسکرول به پایین" -> steps.add(Step(kind = "SCROLL", x = (resources.displayMetrics.widthPixels / 2).toFloat(), y = (resources.displayMetrics.heightPixels / 2).toFloat(), delay = 500L, duration = 500L, scrollDeltaY = 500))
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
        try { panel?.let { wm.updateViewLayout(it, params) } } catch (_: Exception) {}
        getSharedPreferences("touch_loop", MODE_PRIVATE).edit()
            .putInt("panel_width_dp", panelWidthDp).putInt("panel_height_dp", panelHeightDp).apply()
    }

    // ✅ لایه مسدودکننده لمس حین اجرای ماکرو
    private fun showTouchBlocker() {
        if (touchBlocker != null) return
        touchBlocker = View(this).apply {
            setBackgroundColor(0x00000000) // کاملاً شفاف
            // تمام لمس‌ها را مصرف می‌کند تا با ماکرو تداخل نداشته باشند
            setOnTouchListener { _, _ -> true }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // اجازه می‌دهد پنل شناور همچنان کار کند
            PixelFormat.TRANSLUCENT
        )
        try { wm.addView(touchBlocker, params) } catch (_: Exception) {}
    }

    private fun hideTouchBlocker() {
        touchBlocker?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        touchBlocker = null
    }

    private fun showPointPicker() {
        if (!hasValidLicense() || pointPicker != null) return
        val root = FrameLayout(this).apply { setBackgroundColor(0x22000000) }
        val hint = TextView(this).apply {
            text = "برای ثبت کلیک، روی محل موردنظر بزن."; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(18), dp(18), dp(18), dp(18)); setBackgroundColor(0xE6202124.toInt())
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val cancel = Button(this).apply {
            text = "لغو انتخاب"
            setOnClickListener { removePointPicker(); status?.text = "انتخاب لغو شد" }
        }
        root.addView(cancel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        root.setOnTouchListener { _, event ->
            if (!hasValidLicense()) { removePointPicker(); return@setOnTouchListener true }
            if (event.action == MotionEvent.ACTION_UP && pointPicker === root) {
                val x = event.rawX; val y = event.rawY
                removePointPicker()
                addRecordedStep(Step(kind = "TAP", x = x, y = y, delay = 350L))
                ignoreEventsUntil = System.currentTimeMillis() + 800L
                handler.postDelayed({ if (hasValidLicense()) dispatchTap(x, y) { status?.text = "کلیک ثبت شد" } }, 100L)
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
        pointPicker?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        pointPicker = null
    }

    private fun runNext(session: Int = playbackToken) {
        if (!hasValidLicense()) { stopPlayback(); status?.text = "مجوز فعال نیست"; return }
        if (!playing || session != playbackToken || steps.isEmpty()) return
        
        if (playIndex >= steps.size) {
            loopCount++ // ✅ افزایش شمارنده تکرار
            playIndex = 0
            val gap = (100L / playbackSpeed).toLong().coerceIn(20L, 100L)
            handler.postDelayed({ if (hasValidLicense() && playing && session == playbackToken) runNext(session) }, gap)
            return
        }
        updateExecutionReport()
        val step = steps[playIndex++]
        val delay = (step.delay / playbackSpeed).toLong().coerceIn(15L, 20000L)
        handler.postDelayed({
            if (!hasValidLicense()) { stopPlayback(); return@postDelayed }
            if (!playing || session != playbackToken) return@postDelayed
            when (step.kind) {
                "BACK" -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    val backDelay = (400L / playbackSpeed).toLong().coerceIn(50L, 500L)
                    handler.postDelayed({ if (playing && session == playbackToken) runNext(session) }, backDelay)
                }
                "SCROLL" -> performSwipe(step, session)
                "TAP" -> performTap(step, session)
                else -> runNext(session)
            }
        }, delay)
    }

    private fun dispatchTap(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        if (!hasValidLicense()) return
        if (Build.VERSION.SDK_INT >= 24) {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70L)).build()
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { onDone?.invoke() }
                override fun onCancelled(gestureDescription: GestureDescription?) { onDone?.invoke() }
            }, null)
        } else {
            val root = rootInActiveWindow
            try { if (!clickNodeAt(root, x.toInt(), y.toInt())) status?.text = "این کلیک در اندروید ۶ قابل اجرا نیست" }
            finally { root?.recycle() }
            onDone?.invoke()
        }
    }

    private fun performTap(step: Step, session: Int) {
        if (!hasValidLicense()) { stopPlayback(); return }
        if (!playing || session != playbackToken) return
        if (Build.VERSION.SDK_INT >= 24) {
            val path = Path().apply { moveTo(step.x, step.y) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70L)).build()
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { runNext(session) }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (playing && session == playbackToken) handler.postDelayed({ runNext(session) }, 100L)
                }
            }, null)
        } else {
            val root = rootInActiveWindow
            try { if (!clickNodeAt(root, step.x.toInt(), step.y.toInt())) status?.text = "کلیک در اندروید ۶ قابل اجرا نیست" }
            finally { root?.recycle() }
            handler.postDelayed({ runNext(session) }, 150L)
        }
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

    private fun performSwipe(step: Step, session: Int) {
        if (!hasValidLicense()) { stopPlayback(); return }
        if (!playing || session != playbackToken) return
        if (Build.VERSION.SDK_INT < 24) {
            val root = rootInActiveWindow
            val forward = step.scrollDeltaY >= 0
            val scrolled = try { performScrollAtPoint(root, step.x.toInt(), step.y.toInt(), forward) || performScrollOnNode(root, forward) } finally { root?.recycle() }
            if (!scrolled) status?.text = "این صفحه اسکرول قابل‌دسترسی ندارد"
            val baseDuration = if (step.duration > 0) step.duration else (step.delay * 0.8f).toLong().coerceIn(200L, 1500L)
            val duration = (baseDuration / playbackSpeed).toLong().coerceIn(150L, 3000L)
            handler.postDelayed({ if (playing && session == playbackToken) runNext(session) }, duration)
            return
        }
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        if (screenWidth < 4 || screenHeight < 4) { handler.postDelayed({ runNext(session) }, 200L); return }
        val left = step.left.coerceIn(0, screenWidth - 2)
        val right = step.right.coerceIn(left + 2, screenWidth)
        val top = step.top.coerceIn(0, screenHeight - 2)
        val bottom = step.bottom.coerceIn(top + 2, screenHeight)
        val minY = top + 2f; val maxY = bottom - 2f
        val height = (bottom - top).toFloat().coerceAtLeast(4f)
        val x = step.x.coerceIn(left.toFloat() + 2f, right.toFloat() - 2f)
        val estimated = if (step.scrollDeltaY != 0) abs(step.scrollDeltaY.toFloat()) else height * 0.78f
        val distance = min(height * 0.90f, max(height * 0.45f, estimated * 0.65f))
        val centerY = step.y.coerceIn(minY, maxY)
        val direction = if (step.scrollDeltaY < 0) 1f else -1f
        var startY = (centerY - direction * distance / 2f).coerceIn(minY, maxY)
        var endY = (centerY + direction * distance / 2f).coerceIn(minY, maxY)
        if (abs(startY - endY) < 2f) {
            startY = if (centerY > (minY + maxY) / 2f) maxY else minY
            endY = if (startY == minY) maxY else minY
        }
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val baseDuration = if (step.duration > 0) step.duration else (step.delay * 0.8f).toLong().coerceIn(200L, 1500L)
        val duration = (baseDuration / playbackSpeed).toLong().coerceIn(100L, 3000L)
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        
        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (hasValidLicense() && playing && session == playbackToken) handler.postDelayed({ runNext(session) }, 50L)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (playing && session == playbackToken) { status?.text = "اسکرول لغو شد"; handler.postDelayed({ runNext(session) }, 150L) }
            }
        }, null)
        
        if (!accepted && playing && session == playbackToken) { status?.text = "اجرای اسکرول پذیرفته نشد"; handler.postDelayed({ runNext(session) }, 250L) }
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

    private fun formatSpeed(value: Float): String = String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    private fun updateSpeedLabel() {
        speedLabel?.text = "سرعت ${formatSpeed(playbackSpeed)}×"
        getSharedPreferences("touch_loop", MODE_PRIVATE).edit().putFloat("playback_speed", playbackSpeed).apply()
    }

    private fun collapseToBubble() {
        if (!hasValidLicense() || panel == null || panelCollapsed) return
        val oldParams = panelParams
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; panelCollapsed = true
        val size = dp(52)
        val bubbleView = TextView(this).apply {
            text = "TL"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF4258A8.toInt())
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
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) expandFromBubble(); true }
                else -> true
            }
        }
        bubble = bubbleView
        try { wm.addView(bubbleView, params) } catch (_: Exception) { bubble = null; panelCollapsed = false; showPanel() }
    }

    private fun expandFromBubble() {
        if (!hasValidLicense()) { removePanel(); return }
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val oldParams = bubbleParams; bubble = null; bubbleParams = null; panelCollapsed = false
        showPanel()
        val params = panelParams
        if (params != null && oldParams != null) {
            params.x = oldParams.x; params.y = oldParams.y
            try { panel?.let { wm.updateViewLayout(it, params) } } catch (_: Exception) {}
        }
    }

    // ✅ اصلاح باگ توقف: فقط وضعیت را ریست می‌کند، مراحل را حذف نمی‌کند
    private fun stopPlayback() { 
        playing = false
        playbackToken++
        playIndex = 0
        loopCount = 0
        executionReport?.visibility = View.GONE
        hideTouchBlocker() // حذف لایه مسدودکننده
    }

    private fun stopAll() {
        recording = false; handler.removeCallbacks(flushScrollRunnable); pendingScroll = null
        stopPlayback(); saveMacro()
    }

    private fun saveMacro() {
        val rootObj = JSONObject(); rootObj.put("version", 2)
        val array = JSONArray()
        steps.forEach { step ->
            array.put(JSONObject().apply {
                put("kind", step.kind); put("x", step.x); put("y", step.y)
                put("left", step.left); put("top", step.top); put("right", step.right); put("bottom", step.bottom)
                put("delay", step.delay); put("duration", step.duration); put("scrollDeltaY", step.scrollDeltaY)
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
                steps.add(Step(
                    kind = obj.getString("kind"),
                    x = obj.optDouble("x", 0.0).toFloat(),
                    y = obj.optDouble("y", 0.0).toFloat(),
                    left = obj.optInt("left", 0), top = obj.optInt("top", 0),
                    right = obj.optInt("right", 0), bottom = obj.optInt("bottom", 0),
                    delay = obj.optLong("delay", 350L),
                    duration = obj.optLong("duration", 0L),
                    scrollDeltaY = obj.optInt("scrollDeltaY", 0)
                ))
            }
        } catch (_: Exception) { steps.clear() }
    }

    private fun removePanel() {
        recording = false; stopPlayback(); handler.removeCallbacks(flushScrollRunnable); pendingScroll = null
        removePointPicker()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; bubble = null; panelParams = null; bubbleParams = null
        panelCollapsed = false; status = null; speedLabel = null; executionReport = null
    }

    override fun onDestroy() { removePanel(); super.onDestroy() }
}
