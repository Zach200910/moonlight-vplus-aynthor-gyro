package com.limelight.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.limelight.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Connecting loader in a Nintendo-console style: a ring of dots that chase around (system
 * spinner) above a thin progress bar. Replaces the V+ "Moonwax" moon-phase loader with the same
 * API: stages only move forward, external progress drives the bar.
 */
class ThorLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    private var accent = ContextCompat.getColor(context, R.color.thor_accent)
    private var stageReached = 0
    private var progress = 0f
    private var spin = 0f
    private var progressAnimator: ValueAnimator? = null
    private val spinAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SPIN_MS
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            spin = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.thor_border)
        refreshAccentColor()
    }

    fun refreshAccentColor() {
        accent = ContextCompat.getColor(context, R.color.thor_accent)
        dotPaint.color = accent
        fillPaint.color = accent
        invalidate()
    }

    fun resetForShow() {
        progressAnimator?.cancel()
        stageReached = 0
        progress = IDLE_FRACTION
        invalidate()
        updateSpin()
    }

    /** Advance to [stage] (1..[STAGE_COUNT]); never moves backwards. */
    fun markStage(stage: Int) {
        if (stage <= stageReached) return
        stageReached = stage.coerceAtMost(STAGE_COUNT)
        animateProgressTo(stageReached.toFloat() / STAGE_COUNT)
    }

    fun setProgress(fraction: Float) {
        val bounded = fraction.coerceIn(0f, 1f)
        stageReached = maxOf(stageReached, (bounded * STAGE_COUNT).toInt().coerceIn(0, STAGE_COUNT))
        animateProgressTo(maxOf(progress, bounded))
    }

    fun setIndeterminate(indeterminate: Boolean) {
        if (!indeterminate) return
        stageReached = 0
        animateProgressTo(IDLE_FRACTION)
    }

    fun markReady() {
        stageReached = STAGE_COUNT
        animateProgressTo(1f)
    }

    private fun animateProgressTo(target: Float) {
        progressAnimator?.cancel()
        if (!animationsEnabled()) {
            progress = target
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(progress, target).apply {
            duration = PROGRESS_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animationsEnabled(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun updateSpin() {
        val shouldSpin = isAttachedToWindow && visibility == VISIBLE && animationsEnabled()
        if (shouldSpin && !spinAnimator.isStarted) spinAnimator.start()
        if (!shouldSpin && spinAnimator.isStarted) spinAnimator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpin()
    }

    override fun onDetachedFromWindow() {
        spinAnimator.cancel()
        progressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateSpin()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((WIDTH_DP * density).toInt(), widthMeasureSpec),
            resolveSize((HEIGHT_DP * density).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = SPINNER_RADIUS_DP * density + DOT_RADIUS_DP * density * 1.6f
        val ringRadius = SPINNER_RADIUS_DP * density
        val dotRadius = DOT_RADIUS_DP * density
        val head = (spin * DOTS).toInt()
        for (i in 0 until DOTS) {
            val angle = (i.toDouble() / DOTS) * Math.PI * 2 - Math.PI / 2
            // Dots fade behind the leading dot, like a console system spinner.
            val behind = ((head - i) % DOTS + DOTS) % DOTS
            dotPaint.alpha = (255 * (1f - behind / DOTS.toFloat() * 0.85f)).toInt()
            canvas.drawCircle(
                cx + (ringRadius * cos(angle)).toFloat(),
                cy + (ringRadius * sin(angle)).toFloat(),
                dotRadius, dotPaint
            )
        }

        val barWidth = BAR_WIDTH_DP * density
        val barHeight = BAR_HEIGHT_DP * density
        val top = height - barHeight - density
        bar.set(cx - barWidth / 2, top, cx + barWidth / 2, top + barHeight)
        canvas.drawRoundRect(bar, barHeight, barHeight, trackPaint)
        bar.right = bar.left + barWidth * progress.coerceIn(0f, 1f)
        if (bar.width() > 0f) canvas.drawRoundRect(bar, barHeight, barHeight, fillPaint)
    }

    private companion object {
        const val STAGE_COUNT = 4
        const val IDLE_FRACTION = 0.06f
        const val DOTS = 8
        const val SPIN_MS = 960L
        const val PROGRESS_MS = 160L
        const val SPINNER_RADIUS_DP = 22f
        const val DOT_RADIUS_DP = 4f
        const val BAR_WIDTH_DP = 200f
        const val BAR_HEIGHT_DP = 4f
        const val WIDTH_DP = 220f
        const val HEIGHT_DP = 84f
    }
}
