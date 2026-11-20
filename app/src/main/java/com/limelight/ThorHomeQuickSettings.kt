package com.limelight

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.StreamSettings

/**
 * Thor bottom screen while no stream is running: quick stream settings (resolution, frame rate,
 * bitrate, codec, HDR, performance overlay). Values are written to the same preferences the
 * Settings screen uses, so they apply to the next stream. The window is not focusable, so the
 * controller keeps driving the top screen.
 */
class ThorHomeQuickSettings(private val activity: Activity) {
    private var presentation: QuickPresentation? = null

    fun show() {
        if (presentation != null) {
            presentation?.refresh()
            return
        }
        val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        @Suppress("DEPRECATION")
        val primaryId = activity.windowManager.defaultDisplay.displayId
        val display = builtInSecondDisplay(displayManager, primaryId) ?: return
        val created = QuickPresentation(activity, display)
        try {
            created.show()
            presentation = created
        } catch (e: WindowManager.InvalidDisplayException) {
            LimeLog.warning("Bottom-screen quick settings unavailable: ${e.message}")
        } catch (e: RuntimeException) {
            LimeLog.warning("Bottom-screen quick settings failed: $e")
        }
    }

    fun dismiss() {
        val current = presentation ?: return
        presentation = null
        try {
            current.dismiss()
        } catch (e: RuntimeException) {
            LimeLog.warning("Bottom-screen quick settings dismiss failed: $e")
        }
    }

    // Same detection as the in-stream bottom screen (SecondScreenTrackpad).
    private fun builtInSecondDisplay(displayManager: DisplayManager, primaryDisplayId: Int): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
            it.isValid && it.displayId != primaryDisplayId &&
                (it.flags and Display.FLAG_PRESENTATION) != 0 &&
                it.state != Display.STATE_OFF && isBuiltIn(it)
        }

    private fun isBuiltIn(display: Display): Boolean {
        val type = runCatching { Display::class.java.getMethod("getType").invoke(display) as Int }.getOrNull()
        if (type != null) return type == TYPE_INTERNAL
        val model = Build.MODEL.trim().lowercase()
        return KNOWN_DUAL_SCREEN_MODELS.any { model.startsWith(it) }
    }

    private inner class QuickPresentation(context: Context, display: Display) : Presentation(context, display) {
        private val rows = mutableListOf<() -> Unit>()

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window?.setBackgroundDrawable(ColorDrawable(color(R.color.thor_background)))
            setContentView(buildContent())
            refresh()
        }

        fun refresh() {
            rows.forEach { it() }
        }

        private fun buildContent(): View {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            column.addView(text(context.getString(R.string.thor_quick_title), 22f, R.color.thor_text_primary, bold = true))
            column.addView(text(context.getString(R.string.thor_quick_subtitle), 14f, R.color.thor_text_secondary).apply {
                setPadding(0, dp(4), 0, dp(12))
            })

            addChoiceRow(column, R.string.thor_quick_resolution, RESOLUTIONS.map { it.second },
                current = { p -> RESOLUTIONS.indexOfFirst { !p.isNativeResolution && it.first == "${p.width}x${p.height}" } }
            ) { p, i ->
                val (w, h) = RESOLUTIONS[i].first.split('x').map { it.toInt() }
                p.width = w
                p.height = h
                p.isNativeResolution = false
                p.isCustomResolution = false
            }
            addChoiceRow(column, R.string.thor_quick_fps, FPS.map { "$it" },
                current = { p -> FPS.indexOf(p.fps) }
            ) { p, i -> p.fps = FPS[i] }
            addStepperRow(column)
            addChoiceRow(column, R.string.thor_quick_codec, CODECS.map { context.getString(it.second) },
                current = { p -> CODECS.indexOfFirst { it.first == p.videoFormat } }
            ) { p, i -> p.videoFormat = CODECS[i].first }
            addChoiceRow(column, R.string.thor_quick_hdr, listOf(context.getString(R.string.thor_quick_off), context.getString(R.string.thor_quick_on)),
                current = { p -> if (p.enableHdr) 1 else 0 }
            ) { p, i -> p.enableHdr = i == 1 }
            addChoiceRow(column, R.string.thor_quick_sharpen,
                listOf(R.string.thor_quick_off, R.string.thor_quick_low, R.string.thor_quick_medium, R.string.thor_quick_high)
                    .map { context.getString(it) },
                current = { p ->
                    if (p.sharpenStrength <= 0) 0
                    else (1..3).minByOrNull { kotlin.math.abs(SHARPEN_LEVELS[it] - p.sharpenStrength) } ?: 0
                }
            ) { p, i -> p.sharpenStrength = SHARPEN_LEVELS[i] }
            addChoiceRow(column, R.string.thor_quick_overlay, listOf(context.getString(R.string.thor_quick_off), context.getString(R.string.thor_quick_on)),
                current = { p -> if (p.enablePerfOverlay) 1 else 0 }
            ) { p, i -> p.enablePerfOverlay = i == 1 }

            column.addView(chip(context.getString(R.string.thor_quick_all_settings)) {
                activity.startActivity(Intent(activity, StreamSettings::class.java))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })

            return ScrollView(context).apply {
                isFillViewport = true
                addView(column)
            }
        }

        private fun addChoiceRow(
            parent: LinearLayout,
            labelRes: Int,
            labels: List<String>,
            current: (PreferenceConfiguration) -> Int,
            onPick: (PreferenceConfiguration, Int) -> Unit
        ) {
            parent.addView(sectionLabel(labelRes))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val chips = labels.mapIndexed { i, label ->
                chip(label) { update { p -> onPick(p, i) } }.also {
                    row.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (i > 0) marginStart = dp(8)
                    })
                }
            }
            parent.addView(row)
            rows += {
                val selected = current(read())
                chips.forEachIndexed { i, c -> setChipSelected(c, i == selected) }
            }
        }

        private fun addStepperRow(parent: LinearLayout) {
            parent.addView(sectionLabel(R.string.thor_quick_bitrate))
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val value = text("", 18f, R.color.thor_text_primary, bold = true).apply { gravity = Gravity.CENTER }
            val minus = chip("−") { stepBitrate(-1) }
            val plus = chip("+") { stepBitrate(1) }
            row.addView(minus, LinearLayout.LayoutParams(dp(72), dp(48)))
            row.addView(value, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(plus, LinearLayout.LayoutParams(dp(72), dp(48)))
            parent.addView(row)
            rows += {
                val kbps = read().bitrate
                value.text = context.getString(R.string.thor_quick_mbps, kbps / 1000f)
            }
        }

        private fun stepBitrate(direction: Int) {
            update { p ->
                val currentMbps = p.bitrate / 1000f
                val next = if (direction > 0) {
                    BITRATES_MBPS.firstOrNull { it > currentMbps + 0.01f } ?: BITRATES_MBPS.last()
                } else {
                    BITRATES_MBPS.lastOrNull { it < currentMbps - 0.01f } ?: BITRATES_MBPS.first()
                }
                p.bitrate = next * 1000
            }
        }

        private fun read(): PreferenceConfiguration = PreferenceConfiguration.readPreferences(activity)

        private fun update(change: (PreferenceConfiguration) -> Unit) {
            val prefs = read()
            change(prefs)
            if (!prefs.writeScenePreferences(activity)) {
                LimeLog.warning("Quick settings: failed to save preferences")
            }
            // Sharpening is not part of the stream-settings bundle above; store it directly.
            PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putInt(PreferenceConfiguration.SHARPEN_STRENGTH_PREF_STRING, prefs.sharpenStrength)
                .apply()
            refresh()
        }

        // ------------------------------------------------------------ views

        private fun sectionLabel(res: Int) = text(context.getString(res), 14f, R.color.thor_text_secondary).apply {
            setPadding(0, dp(14), 0, dp(6))
        }

        private fun text(value: String, sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color(colorRes))
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.thor_text_primary))
            background = ContextCompat.getDrawable(context, R.drawable.thor_quick_chip)
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }

        private fun setChipSelected(chip: TextView, selected: Boolean) {
            chip.isSelected = selected
            chip.setTextColor(color(if (selected) R.color.thor_accent else R.color.thor_text_primary))
        }

        private fun color(res: Int) = ContextCompat.getColor(context, res)

        private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val TYPE_INTERNAL = 1
        val KNOWN_DUAL_SCREEN_MODELS = setOf("retroid pocket duo", "ayn thor")
        val RESOLUTIONS = listOf("1280x720" to "720p", "1920x1080" to "1080p", "2560x1440" to "1440p", "3840x2160" to "4K")
        val FPS = listOf(30, 60, 90, 120)
        val SHARPEN_LEVELS = listOf(0, 30, 55, 80)
        val BITRATES_MBPS = listOf(5, 10, 15, 20, 30, 40, 50, 60, 80, 100, 120, 150)
        val CODECS = listOf(
            PreferenceConfiguration.FormatOption.AUTO to R.string.thor_quick_codec_auto,
            PreferenceConfiguration.FormatOption.FORCE_H264 to R.string.thor_quick_codec_h264,
            PreferenceConfiguration.FormatOption.FORCE_HEVC to R.string.thor_quick_codec_hevc,
            PreferenceConfiguration.FormatOption.FORCE_AV1 to R.string.thor_quick_codec_av1
        )
    }
}
