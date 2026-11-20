package com.limelight.preferences

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import com.limelight.R

/**
 * Shared row surfaces for both levels of settings navigation.
 *
 * Thor (Switch System Settings style): each group is a flat surface whose rows are separated
 * by thin dividers; the focused row gets a bold accent frame, the pressed row a lighter fill.
 */
internal object SettingsCardStyle {
    fun background(context: Context, top: Boolean, bottom: Boolean): StateListDrawable {
        val res = context.resources
        val density = res.displayMetrics.density
        val radius = res.getDimension(R.dimen.thor_radius_small)
        val surface = ContextCompat.getColor(context, R.color.thor_surface)
        val elevated = ContextCompat.getColor(context, R.color.thor_surface_elevated)
        val divider = ContextCompat.getColor(context, R.color.thor_border)
        val accent = ContextCompat.getColor(context, R.color.thor_accent)
        val inset = 16 * density

        fun row(fill: Int, frame: Boolean, showDivider: Boolean) =
            RowDrawable(radius, top, bottom, fill, if (frame) accent else null, 3 * density,
                if (showDivider && !bottom) divider else null, density, inset)

        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), row(surface, frame = false, showDivider = true))
            addState(intArrayOf(android.R.attr.state_focused), row(elevated, frame = true, showDivider = false))
            addState(intArrayOf(android.R.attr.state_pressed), row(elevated, frame = false, showDivider = false))
            addState(intArrayOf(), row(surface, frame = false, showDivider = true))
        }
    }

    private class RowDrawable(
        private val radius: Float,
        private val roundTop: Boolean,
        private val roundBottom: Boolean,
        fill: Int,
        frameColor: Int?,
        private val frameWidth: Float,
        dividerColor: Int?,
        private val dividerWidth: Float,
        private val dividerInset: Float
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        private val framePaint = frameColor?.let {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = frameWidth
                color = it
            }
        }
        private val dividerPaint = dividerColor?.let { Paint().apply { color = it } }
        private val rect = RectF()
        private val path = Path()

        private fun roundedPath(r: RectF, rad: Float): Path {
            val t = if (roundTop) rad else 0f
            val b = if (roundBottom) rad else 0f
            path.reset()
            path.addRoundRect(r, floatArrayOf(t, t, t, t, b, b, b, b), Path.Direction.CW)
            return path
        }

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            canvas.drawPath(roundedPath(rect, radius), fillPaint)
            dividerPaint?.let {
                canvas.drawRect(rect.left + dividerInset, rect.bottom - dividerWidth,
                    rect.right - dividerInset, rect.bottom, it)
            }
            framePaint?.let {
                val half = frameWidth / 2f
                rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
                // A focused row is always fully framed, like a Switch selection box.
                path.reset()
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
                canvas.drawPath(path, it)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            framePaint?.alpha = alpha
            dividerPaint?.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            framePaint?.colorFilter = colorFilter
            dividerPaint?.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
