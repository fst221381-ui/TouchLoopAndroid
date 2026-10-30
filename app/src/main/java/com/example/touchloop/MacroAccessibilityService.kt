package com.example.touchloop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
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
        val delay: Long = 350L
    )

    private val handler = Handler(Looper.getMainLooper())
    private val steps = mutableListOf<Step>()
    private var recording = false
    private var playing = false
    private var playIndex = 0
    private var lastEventAt = 0L
    private var panel: View? = null
    private lateinit var wm: WindowManager
    private var status: TextView? = null

    // Accessibility may emit several scroll events for one finger swipe.
    // Debounce them so one user scroll becomes one replay step.
    private var pendingScroll: Step? = null
    private val flushScrollRunnable = Runnable { flushPendingScroll() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        loadMacro()
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !recording || playing) return
        if (event.packageName?.toString() == packageName) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val source = event.source ?: return
        val bounds = Rect()
        try {
            source.getBoundsInScreen(bounds)
        } finally {
            source.recycle()
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = System.currentTimeMillis()
            val delay = if (lastEventAt == 0L) 350L else (now - lastEventAt).coerceIn(120L, 5000L)
            pendingScroll = Step(
                kind = "SCROLL",
                x = bounds.exactCenterX(),
                y = bounds.exactCenterY(),
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                delay = delay
            )
            handler.removeCallbacks(flushScrollRunnable)
            handler.postDelayed(flushScrollRunnable, 300L)
        } else {
            // Keep the sequence correct if a click immediately follows a scroll.
            handler.removeCallbacks(flushScrollRunnable)
            flushPendingScroll()
            addRecordedStep(
                Step(
                    kind = "TAP",
                    x = bounds.exactCenterX(),
                    y = bounds.exactCenterY(),
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom
                )
            )
        }
    }

    private fun flushPendingScroll() {
        val scroll = pendingScroll ?: return
        pendingScroll = null
        if (recording && !playing) addRecordedStep(scroll)
    }

    private fun addRecordedStep(step: Step) {
        steps.add(step)
        lastEventAt = System.currentTimeMillis()
        status?.text = "ضبط: ${steps.size} مرحله"
    }

    override fun onInterrupt() {
        stopAll()
    }

    private fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xEE202124.toInt())
        }
        status = TextView(this).apply {
            text = "Touch Loop آماده است"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        root.addView(status)
        root.addView(button("● Record") {
            stopPlayback()
            handler.removeCallbacks(flushScrollRunnable)
            pendingScroll = null
            steps.clear()
            recording = true
            lastEventAt = 0L
            status?.text = "در حال ضبط…"
        })
        root.addView(button("↩ بازگشت / ثبت خروج") {
            if (recording && !playing) {
                handler.removeCallbacks(flushScrollRunnable)
                flushPendingScroll()
                addRecordedStep(Step(kind = "BACK"))
                status?.text = "مرحله بازگشت ثبت شد (${steps.size})"
                // Perform the same back action now, so recording continues from the list screen.
                performGlobalAction(GLOBAL_ACTION_BACK)
            } else if (playing) {
                status?.text = "برای خروج از پخش، Stop را بزن"
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        })
        root.addView(button("■ Stop") {
            if (recording) {
                handler.removeCallbacks(flushScrollRunnable)
                flushPendingScroll()
            }
            recording = false
            stopPlayback()
            saveMacro()
            status?.text = "متوقف شد؛ ${steps.size} مرحله ذخیره شد"
        })
        root.addView(button("▶ Play loop") {
            if (recording) {
                handler.removeCallbacks(flushScrollRunnable)
                flushPendingScroll()
            }
            recording = false
            saveMacro()
            if (steps.isEmpty()) {
                status?.text = "اول یک ماکرو ضبط کن"
            } else {
                stopPlayback()
                playing = true
                playIndex = 0
                status?.text = "در حال تکرار…"
                runNext()
            }
        })
        root.addView(button("× بستن پنل") { removePanel() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 140
        }
        panel = root
        try { wm.addView(root, params) } catch (_: Exception) { panel = null }
    }

    private fun runNext() {
        if (!playing || steps.isEmpty()) return
        if (playIndex >= steps.size) playIndex = 0
        val step = steps[playIndex++]
        handler.postDelayed({
            if (!playing) return@postDelayed
            when (step.kind) {
                "BACK" -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    // Allow the app to finish its navigation transition before the next step.
                    handler.postDelayed({ runNext() }, 650L)
                }
                "SCROLL" -> performSwipe(step)
                else -> performTap(step)
            }
        }, step.delay.coerceIn(120L, 5000L))
    }

    private fun performTap(step: Step) {
        val path = Path().apply { moveTo(step.x, step.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { runNext() }
            override fun onCancelled(gestureDescription: GestureDescription?) { runNext() }
        }, null)
    }

    private fun performSwipe(step: Step) {
        val left = max(0, step.left)
        val right = max(left + 2, step.right)
        val top = max(0, step.top)
        val bottom = max(top + 2, step.bottom)
        val x = min(right - 2f, max(left + 2f, step.x))
        val startY = min(bottom - 2f, max(top + 2f, step.y + 80f))
        val endY = min(bottom - 2f, max(top + 2f, step.y - 80f))
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { runNext() }
            override fun onCancelled(gestureDescription: GestureDescription?) { runNext() }
        }, null)
    }

    private fun stopPlayback() {
        playing = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun stopAll() {
        recording = false
        handler.removeCallbacks(flushScrollRunnable)
        pendingScroll = null
        stopPlayback()
        saveMacro()
    }

    private fun saveMacro() {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().apply {
                put("kind", s.kind); put("x", s.x); put("y", s.y)
                put("left", s.left); put("top", s.top); put("right", s.right); put("bottom", s.bottom)
                put("delay", s.delay)
            })
        }
        getSharedPreferences("touch_loop", MODE_PRIVATE).edit().putString("macro", arr.toString()).apply()
    }

    private fun loadMacro() {
        steps.clear()
        val raw = getSharedPreferences("touch_loop", MODE_PRIVATE).getString("macro", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                steps.add(
                    Step(
                        kind = o.getString("kind"),
                        x = o.optDouble("x", 0.0).toFloat(),
                        y = o.optDouble("y", 0.0).toFloat(),
                        left = o.optInt("left", 0),
                        top = o.optInt("top", 0),
                        right = o.optInt("right", 0),
                        bottom = o.optInt("bottom", 0),
                        delay = o.optLong("delay", 350L)
                    )
                )
            }
        } catch (_: Exception) { steps.clear() }
    }

    private fun removePanel() {
        stopAll()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        status = null
    }

    override fun onDestroy() {
        removePanel()
        super.onDestroy()
    }
}
