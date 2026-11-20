package com.limelight.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.limelight.binding.input.GyroRatchet
import com.limelight.binding.input.GyroRatchetPlacement

/**
 * Small hold-to-pause button: while it is held, gyro input stops (see [GyroRatchet]); releasing it
 * resumes gyro from the device's new position. Shown top-right on the stream or on the bottom-screen
 * touchpad, per [GyroRatchetPlacement].
 *
 * Double-tap, then drag (keep the finger down on the second tap) to move it. The position is saved
 * per screen as a fraction of the parent's size, so it survives sessions and rotation, and is
 * clamped so the button always stays fully on screen.
 *
 * @param releaseOnFocusLoss Release when this window loses focus. True on the stream; false on the
 * bottom screen, where touching the top screen moves focus while the button is still held.
 * @param positionKey Which saved position to use: [POSITION_TOP] or [POSITION_BOTTOM].
 */
@SuppressLint("ViewConstructor")
class GyroRatchetButton(
    context: Context,
    private val releaseOnFocusLoss: Boolean = true,
    private val positionKey: String = POSITION_TOP
) : View(context) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val tapMs = ViewConfiguration.getTapTimeout().toLong() + 150L

    // Saved centre as a fraction of the parent's width/height, or null for the default spot.
    private var savedFx: Float? = null
    private var savedFy: Float? = null

    private var moving = false
    private var downTime = 0L
    private var lastTapUpTime = 0L
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTx = 0f
    private var dragStartTy = 0f

    private val parentLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applySavedPosition() }

    init {
        contentDescription = "Hold to pause gyro. Double-tap, then drag to move."
        isHapticFeedbackEnabled = true
        loadPosition()
    }

    override fun onDraw(canvas: Canvas) {
        val held = GyroRatchet.held
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - ring.strokeWidth

        fill.color = when {
            moving -> 0xCC5A8DEE.toInt()
            held -> 0xCC2EC4B6.toInt()
            else -> 0x55000000
        }
        ring.color = if (held) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
        glyph.color = if (held) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, ring)

        // Pause glyph: two bars.
        val barWidth = radius * 0.22f
        val barHeight = radius * 0.9f
        val gap = radius * 0.18f
        canvas.drawRect(cx - gap - barWidth, cy - barHeight / 2, cx - gap, cy + barHeight / 2, glyph)
        canvas.drawRect(cx + gap, cy - barHeight / 2, cx + gap + barWidth, cy + barHeight / 2, glyph)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                if (lastTapUpTime != 0L && event.eventTime - lastTapUpTime <= doubleTapMs) {
                    // Second touch of a double-tap: move instead of pausing.
                    lastTapUpTime = 0L
                    moving = true
                    setHeld(false)
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartTx = translationX
                    dragStartTy = translationY
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                } else {
                    setHeld(true)
                }
            }
            MotionEvent.ACTION_MOVE -> if (moving) {
                translationX = dragStartTx + (event.rawX - dragStartRawX)
                translationY = dragStartTy + (event.rawY - dragStartRawY)
                clampToParent()
            }
            MotionEvent.ACTION_UP -> {
                if (moving) {
                    finishMove()
                } else {
                    setHeld(false)
                    // A quick tap arms the double-tap; a real hold doesn't.
                    lastTapUpTime = if (event.eventTime - downTime <= tapMs) event.eventTime else 0L
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (moving) finishMove() else setHeld(false)
                lastTapUpTime = 0L
            }
        }
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!moving) applySavedPosition()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentLayoutListener)
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        if (moving) finishMove()
        setHeld(false)
        super.onDetachedFromWindow()
    }

    private fun finishMove() {
        moving = false
        clampToParent()
        savePosition()
        invalidate()
    }

    private fun parentSize(): Pair<Int, Int>? {
        val p = parent as? View ?: return null
        return if (p.width > 0 && p.height > 0) p.width to p.height else null
    }

    /** Keep the whole button inside the parent. */
    private fun clampToParent() {
        val (pw, ph) = parentSize() ?: return
        translationX = translationX.coerceIn(-left.toFloat(), (pw - right).toFloat())
        translationY = translationY.coerceIn(-top.toFloat(), (ph - bottom).toFloat())
    }

    /** Move the button so its centre sits at the saved fraction of the parent (or its default spot). */
    private fun applySavedPosition() {
        val fx = savedFx
        val fy = savedFy
        if (fx == null || fy == null || width == 0) {
            translationX = 0f
            translationY = 0f
            return
        }
        val (pw, ph) = parentSize() ?: return
        translationX = fx * pw - (left + width / 2f)
        translationY = fy * ph - (top + height / 2f)
        clampToParent()
    }

    private fun loadPosition() {
        val x = prefs.getFloat(keyX(), Float.NaN)
        val y = prefs.getFloat(keyY(), Float.NaN)
        if (!x.isNaN() && !y.isNaN()) {
            savedFx = x.coerceIn(0f, 1f)
            savedFy = y.coerceIn(0f, 1f)
        }
    }

    private fun savePosition() {
        val (pw, ph) = parentSize() ?: return
        val fx = ((left + translationX + width / 2f) / pw).coerceIn(0f, 1f)
        val fy = ((top + translationY + height / 2f) / ph).coerceIn(0f, 1f)
        savedFx = fx
        savedFy = fy
        prefs.edit {
            putFloat(keyX(), fx)
            putFloat(keyY(), fy)
        }
    }

    private fun keyX() = "${POSITION_PREF_PREFIX}${positionKey}_x"
    private fun keyY() = "${POSITION_PREF_PREFIX}${positionKey}_y"

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus && releaseOnFocusLoss) setHeld(false)
        super.onWindowFocusChanged(hasWindowFocus)
    }

    private fun setHeld(held: Boolean) {
        if (GyroRatchet.held == held) return
        GyroRatchet.held = held
        if (held) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    companion object {
        const val SIZE_DP = 40
        private const val MARGIN_DP = 12

        const val POSITION_TOP = "top"
        const val POSITION_BOTTOM = "bottom"
        private const val POSITION_PREF_PREFIX = "gyro_ratchet_pos_"

        /** Put the button back in its default corner on both screens. */
        fun resetPositions(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                listOf(POSITION_TOP, POSITION_BOTTOM).forEach {
                    remove("${POSITION_PREF_PREFIX}${it}_x")
                    remove("${POSITION_PREF_PREFIX}${it}_y")
                }
            }
        }

        fun deviceHasGyro(context: Context): Boolean {
            val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            return sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        }

        /**
         * Show the stream (top-screen) button when the setting says so: always for
         * [GyroRatchetPlacement.TOP], and for [GyroRatchetPlacement.BOTTOM] only while no
         * bottom-screen touchpad is showing it.
         *
         * @param button The stream button from [attachIfDeviceHasGyro], or null.
         * @param bottomScreenShowing Whether the bottom-screen touchpad is up.
         */
        fun updateStreamButton(button: GyroRatchetButton?, bottomScreenShowing: Boolean) {
            button ?: return
            val visible = when (GyroRatchetPlacement.read(button.context)) {
                GyroRatchetPlacement.TOP -> true
                GyroRatchetPlacement.BOTTOM -> !bottomScreenShowing
                GyroRatchetPlacement.OFF -> false
            }
            if (!visible) button.setHeld(false)
            button.visibility = if (visible) VISIBLE else GONE
        }

        /**
         * Add the button to [activity]'s content view if the device has a gyroscope.
         * Call [updateStreamButton] to apply the placement setting.
         *
         * @return The added button, or null when the device has no gyroscope.
         */
        fun attachIfDeviceHasGyro(activity: Activity): GyroRatchetButton? {
            if (!deviceHasGyro(activity)) return null

            val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return null
            val density = activity.resources.displayMetrics.density
            val size = (SIZE_DP * density).toInt()
            val margin = (MARGIN_DP * density).toInt()
            val button = GyroRatchetButton(activity, positionKey = POSITION_TOP)
            content.addView(
                button,
                FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                    topMargin = margin
                    marginEnd = margin
                }
            )
            return button
        }
    }
}
