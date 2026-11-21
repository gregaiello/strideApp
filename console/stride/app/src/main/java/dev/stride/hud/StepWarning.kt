package dev.stride.hud

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Next interval in 7" over whatever app is in front (fork-only, 2026-11-21).
 *
 * The point of a session on this console is to run it under a film: Prime or
 * Netflix full screen, the belt doing the work. The page has its own ten-second
 * card (stepWarning in stride-core.js), but the page is not on the screen then,
 * and a rep that arrives with no warning under a film is a rep that catches
 * somebody mid-stride. So the same card is drawn here, over other apps, with
 * the overlay grant the switch button already uses (see [SwitchBubble]).
 *
 * Only while STRIDE is *not* in front, so the two cards never stack. It never
 * takes a touch or focus: it sits over a video player at the moment somebody
 * may reach for the console's own keys, and must never be the thing in the
 * way. Without the overlay grant it simply does nothing.
 */
class StepWarning(context: Context) {

    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var view: LinearLayout? = null
    private var title: TextView? = null
    private var spec: TextView? = null
    private var count: TextView? = null
    private var shownKey = ""

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    /** Call every poll; cheap when nothing changed. Any thread. */
    fun update(label: String?, specText: String, secs: Int) {
        val key = if (label == null) "" else "$label|$specText|$secs"
        if (key == shownKey) return
        shownKey = key
        main.post { if (label == null) hideNow() else showNow(label, specText, secs) }
    }

    fun hide() = update(null, "", 0)

    private fun showNow(label: String, specText: String, secs: Int) {
        if (!SwitchBubble.allowed(ctx)) return
        if (view == null) {
            try { build() } catch (e: Exception) {
                Log.w("Stride", "step warning: could not draw over other apps", e)
                return
            }
        }
        title?.text = label
        spec?.text = specText
        count?.text = secs.toString()
    }

    private fun hideNow() {
        val v = view ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        view = null; title = null; spec = null; count = null
    }

    private fun build() {
        val amber = Color.rgb(0xf0, 0xb4, 0x3c)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22f), dp(16f), dp(22f), dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.argb(0xf0, 8, 11, 13))
                setStroke(dp(2f), amber)
            }
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(ctx).apply {
            text = "NEXT INTERVAL"
            letterSpacing = 0.3f
            setTextColor(amber)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        val t = TextView(ctx).apply {
            setTextColor(Color.rgb(0xee, 0xf3, 0xf4))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            maxLines = 1
        }
        val s = TextView(ctx).apply {
            setTextColor(Color.rgb(0xae, 0xbd, 0xc1))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        }
        column.addView(t); column.addView(s)
        val c = TextView(ctx).apply {
            setTextColor(amber)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 60f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.END
            minWidth = dp(90f)
        }
        card.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(c)

        val p = WindowManager.LayoutParams(
            dp(460f), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40f)
        }
        card.visibility = View.VISIBLE
        wm.addView(card, p)
        view = card; title = t; spec = s; count = c
    }
}
