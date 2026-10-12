package com.example.aamirror.car

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PanModeListener
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.aamirror.CarUiBridge
import com.example.aamirror.Config
import com.example.aamirror.KeyboardBridge
import com.example.aamirror.MirrorEngine

class MirrorCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = MirrorSession()
}
class MirrorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MirrorScreen(carContext)
}

class MirrorScreen(carContext: CarContext) : Screen(carContext) {
    private val appPickerListener: () -> Unit = { screenManager.push(AppSlotScreen(carContext)) }
    private val keyboardListener: (KeyboardBridge.Request) -> Unit = { req ->
        screenManager.push(InputScreen(carContext, req.pkg, req.initialText, req.replaceExisting))
    }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            container.surface?.let { MirrorEngine.attach(it, container.width, container.height, true) }
        }
        override fun onVisibleAreaChanged(visibleArea: Rect) = Unit
        override fun onStableAreaChanged(stableArea: Rect) = MirrorEngine.setVisibleArea(stableArea)
        override fun onSurfaceDestroyed(container: SurfaceContainer) =
            MirrorEngine.detachKeepingPanes(container.surface)
        override fun onScroll(distanceX: Float, distanceY: Float) = MirrorEngine.scroll(distanceX, distanceY)
        override fun onFling(velocityX: Float, velocityY: Float) = MirrorEngine.fling()
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = Unit
        override fun onClick(x: Float, y: Float) = MirrorEngine.click(x, y)
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
            }
            override fun onStart(owner: LifecycleOwner) {
                KeyboardBridge.setListener(keyboardListener)
                CarUiBridge.setAppPickerListener(appPickerListener)
            }
            override fun onStop(owner: LifecycleOwner) {
                KeyboardBridge.clearListener(keyboardListener)
                CarUiBridge.clearAppPickerListener(appPickerListener)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                KeyboardBridge.clearListener(keyboardListener)
                CarUiBridge.clearAppPickerListener(appPickerListener)
                MirrorEngine.shutdown()
            }
        })
    }

    private fun emptyNavigationActionStrip(): ActionStrip = try {
        ActionStrip::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
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

    override fun onGetTemplate(): Template {
        val mapStrip = ActionStrip.Builder().addAction(Action.PAN).build()
        return NavigationTemplate.Builder()
            .setActionStrip(emptyNavigationActionStrip())
            .setMapActionStrip(mapStrip)
            .setPanModeListener(PanModeListener { })
            .build()
    }
}

private data class LaunchableApp(val pkg: String, val label: String)

@Suppress("DEPRECATION")
private fun launchableApps(ctx: CarContext): List<LaunchableApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != ctx.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun appLabel(ctx: CarContext, pkg: String): String {
    if (pkg.isBlank()) return "(Không chọn)"
    return try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }
}

class AppSlotScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val cfg = Config.load()
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("App A").addText(appLabel(carContext, cfg.pkgA))
                .setOnClickListener { screenManager.push(AppSearchScreen(carContext, true)) }.build())
            .addItem(Row.Builder().setTitle("App B").addText(appLabel(carContext, cfg.pkgB))
                .setOnClickListener { screenManager.push(AppSearchScreen(carContext, false)) }.build())
            .build()
        return ListTemplate.Builder().setTitle("Chọn ứng dụng").setHeaderAction(Action.BACK)
            .setSingleList(list).build()
    }
}

class AppSearchScreen(carContext: CarContext, private val isA: Boolean) : Screen(carContext) {
    private val apps by lazy { launchableApps(carContext) }
    private var query = ""
    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) { query = searchText; invalidate() }
        override fun onSearchSubmitted(searchText: String) { query = searchText; invalidate() }
    }
    private fun select(pkg: String) {
        val c = Config.load()
        Config.save(if (isA) c.copy(pkgA = pkg) else c.copy(pkgB = pkg))
        MirrorEngine.reconfigure()
        if (com.example.aamirror.PhoneCaptureState.isActive()) {
            val updated = Config.load()
            com.example.aamirror.MirrorAccessibilityService.startPhoneSplit(
                updated.pkgA, updated.pkgB
            )
        }
        screenManager.popToRoot()
    }
    override fun onGetTemplate(): Template {
        val q = query.trim().lowercase()
        val list = ItemList.Builder()
        if (q.isEmpty()) list.addItem(Row.Builder().setTitle("(Không chọn)")
            .setOnClickListener { select("") }.build())
        apps.asSequence()
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .take(if (q.isEmpty()) 5 else 6)
            .forEach { app ->
                list.addItem(Row.Builder().setTitle(app.label).addText(app.pkg)
                    .setOnClickListener { select(app.pkg) }.build())
            }
        return SearchTemplate.Builder(callback).setHeaderAction(Action.BACK)
            .setSearchHint(if (isA) "Tìm app cho A" else "Tìm app cho B")
            .setShowKeyboardByDefault(false).setItemList(list.build()).build()
    }
}

class InputScreen(
    carContext: CarContext,
    private val targetPkg: String? = null,
    private val initialText: String = "",
    private val replaceExisting: Boolean = false
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val cb = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = Unit
            override fun onSearchSubmitted(searchText: String) {
                MirrorEngine.typeTextTo(targetPkg, searchText, true, replaceExisting)
                screenManager.pop()
            }
        }
        val b = SearchTemplate.Builder(cb).setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true).setSearchHint("Nhập nội dung cho app đang chọn")
        if (initialText.isNotEmpty()) b.setInitialSearchText(initialText)
        return b.build()
    }
}
