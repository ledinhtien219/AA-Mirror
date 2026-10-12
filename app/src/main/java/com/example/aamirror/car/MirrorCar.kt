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
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PanModeListener
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.aamirror.KeyboardBridge
import com.example.aamirror.MirrorEngine

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
                replaceExisting = req.replaceExisting
            )
        )
    }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val s = container.surface ?: return
            currentSurface = s
            MirrorEngine.attach(s, container.width, container.height, showToolbar = true)
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
                // Pause render nhưng giữ VirtualDisplay/task để reconnect không nhân bản app.
                MirrorEngine.shutdown()
            }
        })
    }

    /**
     * NavigationTemplate bắt buộc mActionStrip != null, nhưng AndroidX ActionStrip.Builder lại
     * cấm build strip rỗng. ActionStrip bản thân có private no-arg constructor dành cho serializer
     * và tạo danh sách actions rỗng. Dùng constructor đó để host nhận một strip hợp lệ nhưng không
     * có nút nào để vẽ. Toolbar thật của AA Mirror đã nằm giữa phía trên Surface.
     *
     * Nếu AndroidX thay đổi nội bộ ở bản tương lai, fallback vẫn tạo một action trong suốt để
     * tránh crash; với androidx.car.app 1.4.0 nhánh chính sẽ dùng empty strip.
     */
    private fun emptyNavigationActionStrip(): ActionStrip {
        return try {
            val ctor = ActionStrip::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (_: Throwable) {
            val transparent = androidx.car.app.model.CarIcon.Builder(
                androidx.core.graphics.drawable.IconCompat.createWithResource(
                    carContext, com.example.aamirror.R.drawable.ic_transparent
                )
            ).build()
            ActionStrip.Builder()
                .addAction(Action.Builder().setIcon(transparent).setOnClickListener { }.build())
                .build()
        }
    }

    override fun onGetTemplate(): Template {
        // Không còn nút ActionStrip của host. Toàn bộ control (kể cả tỉ lệ) nằm trên toolbar
        // AA Mirror tự vẽ giữa phía trên màn hình.
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(emptyNavigationActionStrip())
            // Vẫn giữ PAN để Android Auto chuyển swipe/scroll tới SurfaceCallback.
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
