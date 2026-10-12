package com.example.aamirror.car

import android.content.Intent
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PanModeListener
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.aamirror.Config
import com.example.aamirror.KeyboardBridge
import com.example.aamirror.MirrorEngine
import com.example.aamirror.Mode
import com.example.aamirror.R

class MirrorCarService : CarAppService() {
    // Chỉ để thử nghiệm. Khi phát hành thật hãy dùng danh sách host được phép.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = MirrorSession()
}

class MirrorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MirrorScreen(carContext)
}

class MirrorScreen(carContext: CarContext) : Screen(carContext) {

    private var currentSurface: android.view.Surface? = null

    private val keyboardListener: (KeyboardBridge.Request) -> Unit = { req ->
        // Native Android Auto keyboard: mở SearchTemplate ngay sau khi tap vào ô editable.
        screenManager.push(
            InputScreen(
                carContext = carContext,
                targetPkg = req.pkg,
                initialText = req.initialText,
                replaceExisting = true
            )
        )
    }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val s = container.surface ?: return
            currentSurface = s
            MirrorEngine.attach(s, container.width, container.height)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {}
        override fun onStableAreaChanged(stableArea: Rect) = MirrorEngine.setVisibleArea(stableArea)

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            // SearchTemplate (keyboard) có thể làm host thu hồi surface tạm thời.
            // Giữ hai VirtualDisplay sống để chữ vẫn được bơm vào app trong lúc keyboard mở.
            MirrorEngine.detachKeepingPanes(container.surface)
            currentSurface = null
        }

        override fun onScroll(distanceX: Float, distanceY: Float) = MirrorEngine.scroll(distanceX, distanceY)
        override fun onFling(velocityX: Float, velocityY: Float) = MirrorEngine.fling()
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {}
        override fun onClick(x: Float, y: Float) = MirrorEngine.click(x, y)
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
            }

            override fun onStart(owner: LifecycleOwner) {
                KeyboardBridge.setListener(keyboardListener)
            }

            override fun onStop(owner: LifecycleOwner) {
                KeyboardBridge.clearListener(keyboardListener)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                KeyboardBridge.clearListener(keyboardListener)
                MirrorEngine.shutdown()
            }
        })
    }

    private fun icon(res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun action(res: Int, onClick: () -> Unit) =
        Action.Builder().setIcon(icon(res)).setOnClickListener { onClick() }.build()

    private fun toast(text: String) =
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()

    private fun cycleRatio() {
        val c = Config.load()
        val next = when (c.mode) {
            Mode.R30_70 -> Mode.R50_50
            Mode.R50_50 -> Mode.R70_30
            else -> Mode.R30_70
        }
        Config.save(c.copy(mode = next))
        MirrorEngine.reconfigure()
        toast(next.label)
    }

    private fun swap() {
        val c = Config.load()
        Config.save(c.copy(pkgA = c.pkgB, pkgB = c.pkgA))
        MirrorEngine.reconfigure()
        toast("Đổi chỗ A ⇄ B")
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_ratio) { cycleRatio() })
            .addAction(action(R.drawable.ic_swap) { swap() })
            .addAction(action(R.drawable.ic_back) { MirrorEngine.back() })
            .addAction(action(R.drawable.ic_keyboard) { screenManager.push(InputScreen(carContext)) })
            .build()
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(action(R.drawable.ic_refresh) { MirrorEngine.refresh(); toast("Tải lại") })
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .setPanModeListener(PanModeListener { })
            .build()
    }
}

/**
 * Bàn phím native của Android Auto. Với auto mode, initialText được lấy từ AccessibilityNode
 * của app đích và khi Submit sẽ replace toàn bộ nội dung rồi gửi Enter vào đúng VirtualDisplay.
 */
class InputScreen(
    carContext: CarContext,
    private val targetPkg: String? = null,
    private val initialText: String = "",
    private val replaceExisting: Boolean = false
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val cb = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                // Không bơm từng ký tự để tránh backlog shell và giữ bàn phím Android Auto mượt.
                // Chuỗi hoàn chỉnh sẽ được commit khi người dùng nhấn Enter/Search.
            }

            override fun onSearchSubmitted(searchText: String) {
                MirrorEngine.typeTextTo(targetPkg, searchText, true, replaceExisting)
                screenManager.pop()
            }
        }

        val builder = SearchTemplate.Builder(cb)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint("Nhập nội dung cho app đang chọn")

        if (initialText.isNotEmpty()) builder.setInitialSearchText(initialText)
        return builder.build()
    }
}
