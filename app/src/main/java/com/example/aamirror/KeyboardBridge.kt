package com.example.aamirror

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Cầu nối giữa AccessibilityService (nhìn thấy ô nhập của app đang mirror)
 * và CarApp Screen (nơi duy nhất có thể mở SearchTemplate/keyboard của Android Auto).
 */
object KeyboardBridge {
    data class Request(
        val pkg: String,
        val displayId: Int,
        val initialText: String
    )

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((Request) -> Unit)? = null
    private var lastSignature = ""
    private var lastAt = 0L

    @Synchronized
    fun setListener(l: (Request) -> Unit) {
        listener = l
    }

    @Synchronized
    fun clearListener(l: (Request) -> Unit) {
        if (listener === l) listener = null
    }

    fun request(req: Request) {
        val now = SystemClock.uptimeMillis()
        val signature = "${req.pkg}|${req.displayId}|${req.initialText}"
        synchronized(this) {
            // Accessibility thường phát VIEW_CLICKED + VIEW_FOCUSED liên tiếp.
            if (signature == lastSignature && now - lastAt < 2500L) return
            lastSignature = signature
            lastAt = now
        }
        main.post {
            val l = synchronized(this) { listener }
            l?.invoke(req)
        }
    }
}
