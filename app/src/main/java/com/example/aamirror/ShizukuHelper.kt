package com.example.aamirror

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

object ShizukuHelper {
    @Volatile
    var shell: IShellService? = null
        private set

    private lateinit var app: Application
    private var bound = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shell = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            notifyChanged()
            MirrorEngine.onShellReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            bound = false
            notifyChanged()
        }
    }

    fun init(a: Application) {
        app = a
        Shizuku.addBinderReceivedListenerSticky {
            tryBind()
            notifyChanged()
        }
        Shizuku.addBinderDeadListener {
            shell = null
            bound = false
            notifyChanged()
        }
        Shizuku.addRequestPermissionResultListener { _, _ ->
            tryBind()
            notifyChanged()
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.forEach { it() } }

    fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun permitted(): Boolean = binderAlive() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestPermission() {
        if (binderAlive()) try { Shizuku.requestPermission(1001) } catch (_: Throwable) {}
    }

    fun tryBind() {
        if (bound || shell != null || !permitted()) return
        bound = true
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellService::class.java.name))
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(true)
                .version(1)
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            bound = false
        }
    }
}
