package dev.stride.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView

/**
 * One floating button that flips between STRIDE and one other app.
 *
 * Asked for so a walk can carry on under a streaming app: the video fills the
 * panel, and getting back to LAP or END should be one tap rather than a hunt
 * for a Home button the immersive UI hid. It deliberately carries no data and
 * no controls. Elapsed time and speed were considered and dropped: anything
 * worth changing is changed in STRIDE, and a second, tiny copy of the deck's
 * controls floating over a film is the wrong place to touch a running belt.
 *
 * Over STRIDE it shows the other app's icon; over anything else, STRIDE's. The
 * button sits at the right edge and can be dragged up and down, because each
 * of the five interfaces uses the top-right corner for something different and
 * no single fixed spot clears all of them.
 *
 * It is a foreground service because it has to be. A window drawn over other
 * apps needs an owner that outlives the activity going to the background, and
 * on targetSdk 26+ a plain service is stopped about a minute after its app
 * leaves the screen. The side effect is the useful part: a foreground service
 * keeps this process ranked as foreground, so the 5 Hz poll loop in
 * [MainActivity] is not the first thing Android kills when a video app on a
 * MediaTek tablet asks for memory mid-walk.
 */
class SwitchBubble : Service() {

    companion object {
        private const val TAG = "Stride"
        private const val CHANNEL = "switch"
        private const val NOTE_ID = 7
        /** Not a setting, so not in [Settings.json]: where the thumb left it. */
        private const val PREF_Y = "switch_y"

        /**
         * Same process, main thread only. Lets the activity say "I'm in front".
         * Cleared in onDestroy, so it lives exactly as long as the service.
         */
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: SwitchBubble? = null
        @Volatile private var strideInFront = true

        /**
         * The apps the button may flip to, when installed.
         *
         * A list rather than everything with a launcher entry: on a console the
         * launcher list is Settings, Files, a clock and a camera with nothing to
         * point at, and picking one of those for a button over a running belt is
         * never what was meant. Video apps known to install on a Play-less
         * Android 9 console, plus the usual alternatives. Add a package here to
         * offer another one.
         *
         * Google's own YouTube app is left out on purpose. It needs Play
         * services, and on this console Play services checks in, finds a build
         * Google never certified, and a few days later starts replacing YouTube
         * with a "This device isn't Play Protect certified" page (seen
         * 2026-11-09, four days after a clean install). Netflix and Prime
         * Video don't ask; SmartTube plays YouTube without Google at all.
         */
        val VIDEO_APPS = setOf(
            "com.netflix.mediaclient",          // Netflix
            "com.amazon.avod.thirdpartyclient", // Prime Video
            "org.smarttube.stable",             // SmartTube (YouTube without Google Play)
            "org.smarttube.beta",               // SmartTube beta
            "com.teamsmart.videomanager.tv",    // SmartTube, older installs
            "org.schabi.newpipe",               // NewPipe
            "org.polymorphicshade.tubular",     // Tubular, a NewPipe fork
            "app.revanced.android.youtube",     // ReVanced YouTube
            "com.disney.disneyplus",            // Disney+
            "com.plexapp.android",              // Plex
            "org.videolan.vlc",                 // VLC
            "org.xbmc.kodi",                    // Kodi
            "tv.twitch.android.app",            // Twitch
        )

        /** Overlays need a grant; there is no runtime dialog for it. */
        fun allowed(ctx: Context): Boolean = android.provider.Settings.canDrawOverlays(ctx)

        /**
         * Start or stop to match the setting. Called on every resume, so a grant
         * given in the system screen takes effect the moment STRIDE is back.
         */
        fun sync(ctx: Context, cfg: Settings) {
            val want = cfg.switchApp().isNotEmpty() && allowed(ctx)
            val svc = Intent(ctx, SwitchBubble::class.java)
            if (!want) {
                if (instance != null) ctx.stopService(svc)
                return
            }
            if (instance == null) {
                ctx.startForegroundService(svc)
            } else {
                instance?.redraw()
            }
        }

        fun front(inFront: Boolean) {
            strideInFront = inFront
            instance?.redraw()
        }
    }

    private lateinit var wm: WindowManager
    private var button: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val cfg by lazy { Settings(this) }
    private val prefs by lazy { getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTE_ID, notification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            show()
        } catch (e: Exception) {
            // A revoked grant surfaces here as a BadTokenException, not as a
            // false from canDrawOverlays() a moment earlier.
            Log.w(TAG, "switch: cannot draw over other apps (${e.message})")
            stopSelf()
        }
    }

    override fun onDestroy() {
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        button = null
        instance = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /**
     * The status bar is hidden on this console, so nobody sees this. It exists
     * because a foreground service may not run without one; MIN keeps it out of
     * the way on a panel that does show a shade.
     */
    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "App switch button", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("STRIDE")
            .setContentText("Switch button is on")
            .setOngoing(true)
            .build()
    }

    private fun show() {
        val size = dp(64)
        val v = ImageView(this).apply {
            // The icon fills the disc and is clipped to it. Launcher icons
            // arrive square on this console (Netflix's red tile, NewPipe's),
            // and a square inside a round button read as two shapes fighting.
            // Clipping to the view's oval outline makes every icon round,
            // whatever shape its app or the ROM's icon mask gave it.
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE0141312.toInt())
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            // The ring goes on top of the icon rather than under it, so it
            // still separates the button from a black letterbox or a bright
            // ski slope when the icon reaches the edge.
            foreground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0xB0FFFFFF.toInt())
            }
            alpha = 0.9f
        }
        val p = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = prefs.getInt(PREF_Y, dp(12))
        }
        v.setOnTouchListener(DragOrTap())
        wm.addView(v, p)
        button = v
        params = p
        redraw()
        Log.i(TAG, "switch: button shown (other app: ${cfg.switchApp()})")
    }

    /** Tap switches; a drag past the touch slop moves it up or down instead. */
    private inner class DragOrTap : android.view.View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@SwitchBubble).scaledTouchSlop
        private var downY = 0f
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; startY = p.y; dragging = false
                    v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && Math.abs(dy) > slop) dragging = true
                    if (dragging) {
                        val maxY = resources.displayMetrics.heightPixels - p.height
                        p.y = (startY + dy.toInt()).coerceIn(0, maxOf(0, maxY))
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 0.9f
                    if (dragging) prefs.edit().putInt(PREF_Y, p.y).apply()
                    else { v.performClick(); switch() }
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = 0.9f
            }
            return true
        }
    }

    /** The icon names where a tap goes, not where you are. */
    fun redraw() {
        val v = button ?: return
        val pkg = if (strideInFront) cfg.switchApp() else packageName
        try {
            v.setImageDrawable(packageManager.getApplicationIcon(pkg))
        } catch (e: PackageManager.NameNotFoundException) {
            v.setImageResource(R.mipmap.ic_launcher)
        }
    }

    private fun switch() {
        if (strideInFront) toOther() else toStride()
    }

    private fun toOther() {
        val pkg = cfg.switchApp()
        // The launcher intent resumes the app's existing task when there is
        // one, so a paused stream comes back where it was rather than at its
        // home screen. Whether it carries on *playing* is up to that app.
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Log.w(TAG, "switch: $pkg has no launcher entry (uninstalled or disabled?)")
            return
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Back to STRIDE: the running window, never a new one.
     *
     * This used to send HOME when STRIDE was the home app. When the running
     * window had been started as an ordinary app, HOME opened a second one
     * beside it, with a walk in progress, and two poll loops on one board.
     * See MainActivity.live. Starting the activity is only the fallback for
     * when no window exists at all.
     */
    private fun toStride() {
        if (MainActivity.bringToFront(this)) return
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    }
}
