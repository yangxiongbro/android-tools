package com.screenrecorder.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floating control shown on top of other apps while a recording runs.
 *
 * - tap: pause / resume
 * - long press: stop the recording
 * - drag: move the button
 *
 * The circle colour and glyph mirror the recording state (red with pause bars while recording,
 * slate with a play triangle while paused).
 */
class FloatingControl(
    context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onTogglePause()
        fun onStop()
    }

    enum class State { RECORDING, PAUSED }

    private val appContext = context.applicationContext
    private val windowManager: WindowManager? =
        appContext.getSystemService(WindowManager::class.java)
    private val density = appContext.resources.displayMetrics.density
    private val sizePx = (SIZE_DP * density).toInt()
    private val marginPx = (MARGIN_DP * density).toInt()

    private var controlView: ControlView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show(state: State) {
        if (!canDrawOverlays(appContext)) return
        if (controlView != null) {
            update(state)
            return
        }
        val params = buildLayoutParams()
        val view = ControlView(appContext).apply { this.state = state }
        runCatching { windowManager?.addView(view, params) }
            .onSuccess {
                controlView = view
                layoutParams = params
            }
    }

    fun update(state: State) {
        controlView?.let {
            it.state = state
            it.invalidate()
        }
    }

    fun hide() {
        val view = controlView ?: return
        controlView = null
        layoutParams = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val metrics = appContext.resources.displayMetrics
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - sizePx - marginPx
            y = (metrics.heightPixels * 0.42f).toInt()
        }
    }

    private fun moveTo(x: Int, y: Int) {
        val params = layoutParams ?: return
        val metrics: DisplayMetrics = appContext.resources.displayMetrics
        params.x = x.coerceIn(marginPx / 2, max(marginPx / 2, metrics.widthPixels - sizePx - marginPx / 2))
        params.y = y.coerceIn(marginPx / 2, max(marginPx / 2, metrics.heightPixels - sizePx - marginPx / 2))
        val view = controlView ?: return
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class ControlView(context: Context) : View(context) {

        var state: State = State.RECORDING
            set(value) {
                field = value
                invalidate()
            }

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40000000
            style = Paint.Style.FILL
        }
        private val pauseBar = RectF()
        private val playPath = Path()

        private val handler = Handler(Looper.getMainLooper())
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPressRunnable = Runnable {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callbacks.onStop()
        }

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressFired = false

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - 2f * density

            // Soft shadow so the button stays visible on light backgrounds.
            canvas.drawCircle(cx, cy + 1.5f * density, radius, shadowPaint)

            circlePaint.color = when (state) {
                State.RECORDING -> COLOR_RECORDING
                State.PAUSED -> COLOR_PAUSED
            }
            canvas.drawCircle(cx, cy, radius, circlePaint)

            when (state) {
                State.RECORDING -> drawPauseBars(canvas, cx, cy, radius)
                State.PAUSED -> drawPlayTriangle(canvas, cx, cy, radius)
            }
        }

        private fun drawPauseBars(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val barWidth = radius * 0.24f
            val barHeight = radius * 0.66f
            val gap = radius * 0.20f
            val top = cy - barHeight / 2f
            val bottom = cy + barHeight / 2f
            val corner = barWidth / 2.2f

            pauseBar.set(cx - gap / 2f - barWidth, top, cx - gap / 2f, bottom)
            canvas.drawRoundRect(pauseBar, corner, corner, glyphPaint)
            pauseBar.set(cx + gap / 2f, top, cx + gap / 2f + barWidth, bottom)
            canvas.drawRoundRect(pauseBar, corner, corner, glyphPaint)
        }

        private fun drawPlayTriangle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val size = radius * 0.62f
            val left = cx - size * 0.42f
            playPath.reset()
            playPath.moveTo(left, cy - size * 0.62f)
            playPath.lineTo(left + size, cy)
            playPath.lineTo(left, cy + size * 0.62f)
            playPath.close()
            canvas.drawPath(playPath, glyphPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = this@FloatingControl.layoutParams?.x ?: 0
                    startY = this@FloatingControl.layoutParams?.y ?: 0
                    dragging = false
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        moveTo((startX + dx).toInt(), (startY + dy).toInt())
                    }
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!dragging && !longPressFired) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        callbacks.onTogglePause()
                    }
                }

                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
            }
            return true
        }
    }

    companion object {
        private const val SIZE_DP = 58f
        private const val MARGIN_DP = 12f
        private const val LONG_PRESS_MS = 500L
        private const val COLOR_RECORDING = 0xFFE5484D.toInt()
        private const val COLOR_PAUSED = 0xFF475569.toInt()

        fun canDrawOverlays(context: Context): Boolean =
            Settings.canDrawOverlays(context.applicationContext)
    }
}
