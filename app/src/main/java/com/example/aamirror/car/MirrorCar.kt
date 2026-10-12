package com.example.aamirror.car

import android.content.Intent
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.aamirror.Config
import com.example.aamirror.KeyboardBridge
import com.example.aamirror.MirrorEngine
import com.example.aamirror.Mode

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

    override fun onGetTemplate(): Template {
        // Immersive map surface: không đặt ActionStrip/MapActionStrip.
        // Trên DHU/Android Auto, các strip này làm host thu nhỏ vùng Surface và tạo
        // khoảng đen lớn phía trên/phải. Tỉ lệ/app/DPI chỉnh từ điện thoại; Back và
        // keyboard được route tự động trong engine.
        return NavigationTemplate.Builder().build()
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
