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
import android.widget.FrameLayout
import com.limelight.binding.input.GyroRatchet

/**
 * Small hold-to-pause button in the top-right corner of the stream: while it is held, gyro input
 * stops (see [GyroRatchet]); releasing it resumes gyro from the device's new position.
 */
@SuppressLint("ViewConstructor")
class GyroRatchetButton(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = "Hold to pause gyro"
        isHapticFeedbackEnabled = true
    }

    override fun onDraw(canvas: Canvas) {
        val held = GyroRatchet.held
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - ring.strokeWidth

        fill.color = if (held) 0xCC2EC4B6.toInt() else 0x55000000
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
            MotionEvent.ACTION_DOWN -> setHeld(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setHeld(false)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        setHeld(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) setHeld(false)
        super.onWindowFocusChanged(hasWindowFocus)
    }

    private fun setHeld(held: Boolean) {
        if (GyroRatchet.held == held) return
        GyroRatchet.held = held
        if (held) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        invalidate()
    }

    companion object {
        private const val SIZE_DP = 40
        private const val MARGIN_DP = 12

        /**
         * Add the button to [activity]'s content view if the device has a gyroscope.
         *
         * @return The added button, or null when the device has no gyroscope.
         */
        fun attachIfDeviceHasGyro(activity: Activity): GyroRatchetButton? {
            val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null) return null

            val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return null
            val density = activity.resources.displayMetrics.density
            val size = (SIZE_DP * density).toInt()
            val margin = (MARGIN_DP * density).toInt()
            val button = GyroRatchetButton(activity)
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
