package com.example.aamirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat

object PhoneCaptureState {
    private val lock = Any()
    @Volatile private var active = false
    @Volatile private var w = 0
    @Volatile private var h = 0
    private var image: Image? = null
    private var bitmap: Bitmap? = null

    fun isActive(): Boolean = active
    fun width(): Int = w
    fun height(): Int = h

    fun begin(width: Int, height: Int) {
        w = width
        h = height
        active = true
        MirrorEngine.onPhoneCaptureChanged()
    }

    fun update(img: Image, bmp: Bitmap, width: Int, height: Int) {
        synchronized(lock) {
            val old = image
            image = img
            bitmap = bmp
            w = width
            h = height
            active = true
            try { old?.close() } catch (_: Throwable) {}
        }
        MirrorEngine.onPhoneCaptureFrame()
    }

    fun withBitmap(block: (Bitmap, Int, Int) -> Unit): Boolean {
        synchronized(lock) {
            val b = bitmap ?: return false
            block(b, w, h)
            return true
        }
    }

    fun end() {
        synchronized(lock) {
            try { image?.close() } catch (_: Throwable) {}
            image = null
            bitmap = null
            w = 0
            h = 0
            active = false
        }
        MirrorEngine.onPhoneCaptureChanged()
    }
}

class PhoneCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL = "aamirror_phone_capture"
        private const val NOTIFICATION_ID = 4201

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, PhoneCaptureService::class.java))
        }
    }

    private val thread = HandlerThread("aamirror-phone-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle("AA Mirror")
            .setContentText("Đang chia màn hình điện thoại lên Android Auto")
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        if (projection != null) return START_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val pm = getSystemService(MediaProjectionManager::class.java)
        val p = try { pm.getMediaProjection(resultCode, resultData) } catch (_: Throwable) { null }
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(projectionCallback, handler)

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val density = resources.configuration.densityDpi

        val r = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Throwable) { null }
                ?: return@setOnImageAvailableListener
            val hb = img.hardwareBuffer
            val bmp = if (hb != null) Bitmap.wrapHardwareBuffer(hb, null) else null
            if (bmp == null) {
                img.close()
            } else {
                PhoneCaptureState.update(img, bmp, width, height)
            }
        }, handler)

        try {
            vd = p.createVirtualDisplay(
                "AA Mirror Phone Capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                handler
            )
            PhoneCaptureState.begin(width, height)
            val cfg = Config.load()
            handler.postDelayed({
                MirrorAccessibilityService.startPhoneSplit(cfg.pkgA, cfg.pkgB)
            }, 350)
        } catch (_: Throwable) {
            stopSelf()
        }

        return START_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "AA Mirror Phone Capture",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        reader = null
        vd = null
        projection = null
        PhoneCaptureState.end()
        thread.quitSafely()
        super.onDestroy()
    }
}
