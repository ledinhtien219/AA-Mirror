package com.example.aamirror

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets

/** Xem thử ngay trên điện thoại: cùng engine như màn hình xe, nhưng có cảm ứng thô đầy đủ. */
class PreviewActivity : Activity(), SurfaceHolder.Callback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val sv = SurfaceView(this)
        setContentView(sv)
        sv.holder.addCallback(this)
        sv.setOnTouchListener { _, e ->
            MirrorEngine.rawTouch(e.actionMasked, e.x, e.y, e.downTime, e.eventTime)
            true
        }
        window.insetsController?.hide(WindowInsets.Type.systemBars())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MirrorEngine.attach(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MirrorEngine.detach(holder.surface)
    }
}
