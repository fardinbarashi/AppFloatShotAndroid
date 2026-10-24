package com.fardin.floatshot

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Keeps a small draggable button on top of all apps. A tap hides the button,
 * waits for a screen frame drawn without it, saves that frame as a PNG and
 * shows the button again.
 */
class BubbleService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var bubble: ImageView? = null
    private lateinit var params: WindowManager.LayoutParams

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0

    private lateinit var captureThread: HandlerThread
    private lateinit var capture: Handler

    // Fields below are touched only on the capture thread.
    private var latest: Image? = null
    private var latestAt = 0L
    private var hiddenAt = 0L
    private var waiting = false

    // Main thread only.
    private var busy = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        captureThread = HandlerThread("shot").apply { start() }
        capture = Handler(captureThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be in the foreground before asking for the projection (Android 14+).
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            Toast.makeText(this, R.string.toast_denied, Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(projectionCallback, main)

        startMirror()
        showBubble()
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- capture

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ onFrame(it) }, capture)
        }

    private fun startMirror() {
        val (w, h) = screenSize()
        screenW = w
        screenH = h
        val r = newReader(w, h)
        reader = r
        virtualDisplay = projection?.createVirtualDisplay(
            "FloatShot", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, capture
        )
    }

    /** Runs on the capture thread. Keeps only the newest frame. */
    private fun onFrame(r: ImageReader) {
        if (r != reader) return
        val img = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        latest?.close()
        latest = img
        latestAt = SystemClock.uptimeMillis()
        if (waiting && latestAt >= hiddenAt + SAFE_GAP_MS) deliver()
    }

    /** Called on the capture thread right after the button was hidden. */
    private fun startWaiting(hideTime: Long) {
        hiddenAt = hideTime
        waiting = true
        if (latest != null && latestAt >= hiddenAt + SAFE_GAP_MS) {
            deliver()
            return
        }
        // A still screen may only produce one new frame (the one where the button
        // disappeared). If nothing newer shows up, use the newest frame after hiding.
        capture.postDelayed(giveUp, TIMEOUT_MS)
    }

    private val giveUp = Runnable {
        if (!waiting) return@Runnable
        if (latest != null && latestAt > hiddenAt) {
            deliver()
        } else {
            waiting = false
            main.post { finishShot(false) }
        }
    }

    private fun deliver() {
        waiting = false
        capture.removeCallbacks(giveUp)
        val img = latest ?: return
        latest = null

        val ok = try {
            val bmp = img.toBitmap()
            img.close()
            val saved = savePng(bmp)
            bmp.recycle()
            saved
        } catch (e: Exception) {
            img.close()
            false
        }
        main.post { finishShot(ok) }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun savePng(bmp: Bitmap): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FloatShot_$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FloatShot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val written = contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        } ?: false
        if (!written) {
            contentResolver.delete(uri, null, null)
            return false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capture.post { resizeMirror() }
        main.post { keepBubbleOnScreen() }
    }

    /** Capture thread: follow rotation so screenshots keep the right shape. */
    private fun resizeMirror() {
        val vd = virtualDisplay ?: return
        val (w, h) = screenSize()
        if (w == screenW && h == screenH) return
        screenW = w
        screenH = h
        val old = reader
        val r = newReader(w, h)
        reader = r
        vd.resize(w, h, resources.displayMetrics.densityDpi)
        vd.surface = r.surface
        latest?.close()
        latest = null
        old?.close()
    }

    // ----------------------------------------------------------------- button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val size = dp(52)
        val prefs = getSharedPreferences("bubble", MODE_PRIVATE)

        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", screenW - size - dp(8))
            y = prefs.getInt("y", screenH / 3)
        }

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_camera)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.bubble_desc)
            elevation = dp(6).toFloat()
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            Toast.makeText(this, R.string.toast_closed, Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longFired = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong() * 2)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved && bubble != null) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (longFired) return@setOnTouchListener true
                    if (moved) {
                        snapToEdge()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        takeShot()
                    }
                }
            }
            true
        }

        wm.addView(view, params)
        bubble = view
        keepBubbleOnScreen()
    }

    private fun snapToEdge() {
        val v = bubble ?: return
        val (w, _) = screenSize()
        val target = if (params.x + v.width / 2 < w / 2) dp(4) else w - v.width - dp(4)
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            addUpdateListener {
                if (bubble == null) return@addUpdateListener
                params.x = it.animatedValue as Int
                wm.updateViewLayout(v, params)
            }
            start()
        }
        main.postDelayed({ savePosition() }, 200)
    }

    private fun keepBubbleOnScreen() {
        val v = bubble ?: return
        val (w, h) = screenSize()
        val size = params.width
        params.x = params.x.coerceIn(0, maxOf(0, w - size))
        params.y = params.y.coerceIn(0, maxOf(0, h - size))
        wm.updateViewLayout(v, params)
    }

    private fun savePosition() {
        getSharedPreferences("bubble", MODE_PRIVATE).edit()
            .putInt("x", params.x).putInt("y", params.y).apply()
    }

    private fun takeShot() {
        val v = bubble ?: return
        if (busy || virtualDisplay == null) return
        busy = true
        v.visibility = View.INVISIBLE
        val hideTime = SystemClock.uptimeMillis()
        capture.post { startWaiting(hideTime) }
    }

    private fun finishShot(ok: Boolean) {
        busy = false
        val v = bubble ?: return
        v.visibility = View.VISIBLE
        v.scaleX = 0.6f
        v.scaleY = 0.6f
        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        Toast.makeText(
            this,
            if (ok) R.string.toast_saved else R.string.toast_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ----------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    // ----------------------------------------------------------------- cleanup

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        bubble?.let {
            savePosition()
            wm.removeView(it)
        }
        bubble = null

        val proj = projection
        projection = null
        capture.post {
            virtualDisplay?.release()
            virtualDisplay = null
            latest?.close()
            latest = null
            reader?.close()
            reader = null
            proj?.unregisterCallback(projectionCallback)
            proj?.stop()
            captureThread.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.fardin.floatshot.STOP"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 1

        /** Frames that arrive this long after hiding are sure to be without the button. */
        private const val SAFE_GAP_MS = 60L
        private const val TIMEOUT_MS = 700L
    }
}
