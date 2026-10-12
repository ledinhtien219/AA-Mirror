package com.example.aamirror

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Chạy trong tiến trình quyền "shell" do Shizuku tạo ra.
 * Việc của nó: bơm cảm ứng/phím vào một display cụ thể và chạy lệnh shell (am start --display).
 */
class ShellService : IShellService.Stub() {

    init {
        try { HiddenApiBypass.addHiddenApiExemptions("") } catch (_: Throwable) {}
    }

    private var useShellInput = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private val inputManager: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "input") as IBinder
        Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private val injectMethod by lazy {
        Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private val setDisplayId by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun injectMotion(
        displayId: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long
    ): Boolean {
        if (!useShellInput) {
            try {
                val e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                e.source = InputDevice.SOURCE_TOUCHSCREEN
                setDisplayId.invoke(e, displayId)
                val ok = injectMethod.invoke(inputManager, e, 0) as Boolean
                e.recycle()
                return ok
            } catch (t: Throwable) {
                useShellInput = true // quay về lệnh `input -d`
                if (action == MotionEvent.ACTION_DOWN) { downX = x; downY = y; downAt = eventTime }
            }
        }
        return shellInject(displayId, action, x, y, eventTime)
    }

    private fun shellInject(d: Int, action: Int, x: Float, y: Float, t: Long): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> { downX = x; downY = y; downAt = t }
            MotionEvent.ACTION_UP -> {
                val dur = (t - downAt).coerceIn(50L, 1000L)
                val moved = abs(x - downX) > 12 || abs(y - downY) > 12
                val cmd = if (moved)
                    "input -d $d swipe ${downX.toInt()} ${downY.toInt()} ${x.toInt()} ${y.toInt()} $dur"
                else
                    "input -d $d tap ${x.toInt()} ${y.toInt()}"
                return exec(cmd) == 0
            }
        }
        return true
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val e = KeyEvent(
                    now, now, a, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                )
                setDisplayId.invoke(e, displayId)
                injectMethod.invoke(inputManager, e, 0)
            }
            true
        } catch (t: Throwable) {
            exec("input -d $displayId keyevent $keyCode") == 0
        }
    }

    override fun exec(command: String): Int = try {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor()
    } catch (t: Throwable) {
        -1
    }

    override fun execForOutput(command: String): String = try {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.take(64 * 1024)
    } catch (t: Throwable) {
        ""
    }
}
