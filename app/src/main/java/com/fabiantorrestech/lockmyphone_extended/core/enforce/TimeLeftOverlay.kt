package com.fabiantorrestech.lockmyphone_extended.core.enforce

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.fabiantorrestech.lockmyphone_extended.core.lock.LimitClock
import com.fabiantorrestech.lockmyphone_extended.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The time left in a Limited or Timed apps session: a small pill over the app in front while the
 * session counts. It's an accessibility overlay (the guard's own window), so it needs no "Display
 * over other apps" permission. Touches outside the pill reach the app; the pill can be dragged and
 * remembers where. It turns amber at 1 minute left and red at 15 seconds.
 *
 * @param settings read only while a session counts, so after the first unlock (it's credential-protected).
 */
class TimeLeftOverlay(
    private val service: AccessibilityService,
    private val limitClock: StateFlow<LimitClock?>,
    private val settings: () -> SettingsStore,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pill: TimeLeftPill? = null
    private var params: WindowManager.LayoutParams? = null
    private val tick = Runnable { refresh() }

    fun start() {
        scope.launch { limitClock.collect { refresh() } }
    }

    fun stop() {
        scope.cancel()
        handler.removeCallbacks(tick)
        hide()
    }

    /** Shows, updates or hides the pill, and ticks again on the next whole second. */
    private fun refresh() {
        handler.removeCallbacks(tick)
        val clock = limitClock.value
        val store = clock?.let { settings() }
        if (clock == null || store == null || !store.showTimeLeft.value) {
            hide()
            return
        }
        val leftMs = (clock.leftMs - (System.currentTimeMillis() - clock.atMs)).coerceAtLeast(0L)
        val view = pill ?: show(store) ?: return
        val seconds = (leftMs + 999) / 1_000
        view.text = "⏱ %d:%02d".format(seconds / 60, seconds % 60)
        (view.background as GradientDrawable).setColor(
            when {
                leftMs <= LATE_MS -> RED
                leftMs <= EARLY_MS -> AMBER
                else -> DARK
            }
        )
        if (leftMs > 0) handler.postDelayed(tick, (leftMs - 1) % 1_000 + 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(store: SettingsStore): TimeLeftPill? {
        val large = store.timeLeftLarge.value
        val density = service.resources.displayMetrics.density
        val view = TimeLeftPill(service).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (large) 17f else 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val h = ((if (large) 14 else 10) * density).toInt()
            val v = ((if (large) 6 else 4) * density).toInt()
            setPadding(h, v, h, v)
            background = GradientDrawable().apply { cornerRadius = 100 * density }
        }
        val statusBar = windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val saved = store.timeLeftOffset()
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Measured from the top right corner, so a pill that grows stays put on the right.
            gravity = Gravity.TOP or Gravity.END
            x = saved?.first ?: (12 * density).toInt()
            y = saved?.second ?: (statusBar + 8 * density).toInt()
        }
        view.setOnTouchListener(DragListener(layout, store))
        try {
            windowManager.addView(view, layout)
        } catch (e: RuntimeException) {
            return null
        }
        pill = view
        params = layout
        return view
    }

    private fun hide() {
        val view = pill ?: return
        pill = null
        params = null
        runCatching { windowManager.removeView(view) }
    }

    /** Moves the pill with the finger and saves where it ends up; a tap does nothing. */
    private inner class DragListener(
        private val layout: WindowManager.LayoutParams,
        private val store: SettingsStore,
    ) : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                }
                MotionEvent.ACTION_MOVE -> {
                    // x counts from the right edge (Gravity.END), so moving right makes it smaller.
                    layout.x = (startX - (event.rawX - downX)).toInt().coerceAtLeast(0)
                    layout.y = (startY + (event.rawY - downY)).toInt().coerceAtLeast(0)
                    runCatching { windowManager.updateViewLayout(view, layout) }
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) + abs(event.rawY - downY) > 4) store.setTimeLeftOffset(layout.x, layout.y)
                }
            }
            return true
        }
    }

    /**
     * The pill's view. Its window reports itself to the accessibility service like an app would,
     * under the root view's accessibility class name; this one is unique, so the service can tell
     * it apart and ignore it (it isn't this app coming to the front).
     */
    class TimeLeftPill(context: android.content.Context) : TextView(context) {
        override fun getAccessibilityClassName(): CharSequence = CLASS_NAME

        companion object {
            const val CLASS_NAME = "com.fabiantorrestech.lockmyphone_extended.TimeLeftPill"
        }
    }

    private companion object {
        const val EARLY_MS = 60_000L
        const val LATE_MS = 15_000L
        const val DARK = 0xCC202020.toInt()
        const val AMBER = 0xE6A86400.toInt()
        const val RED = 0xE6B3261E.toInt()
    }
}
