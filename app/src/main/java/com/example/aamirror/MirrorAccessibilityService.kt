package com.example.aamirror

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Phát hiện ô nhập liệu đang focus trên các VirtualDisplay của AA Mirror và yêu cầu
 * CarApp mở SearchTemplate, từ đó Android Auto hiển thị bàn phím native.
 *
 * Điểm quan trọng: getWindows() thường chỉ trả cửa sổ của default display. AA Mirror chạy
 * app trên VirtualDisplay, vì vậy phải dùng getWindowsOnAllDisplays() (minSdk 31).
 */
class MirrorAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: MirrorAccessibilityService? = null

        /**
         * Được gọi ngay sau một tap từ Android Auto. Probe nhiều nhịp vì một số app cập nhật
         * input focus chậm hơn ACTION_UP 1-2 frame.
         */
        fun probeAfterCarTap(pkg: String, displayId: Int) {
            instance?.scheduleProbe(pkg, displayId)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Chỉ phản ứng với package/display vừa được người dùng tap trên Android Auto.
        if (!MirrorEngine.shouldOpenAutoKeyboard(pkg, event.displayId)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val node = editableFromEventOrDisplay(event, pkg, event.displayId) ?: return
                requestKeyboard(pkg, event.displayId, node)
            }
        }
    }

    private fun scheduleProbe(pkg: String, displayId: Int) {
        // 120 ms thường đủ với EditText native; 320/650 ms bắt Compose/WebView/app nặng.
        for (delay in longArrayOf(120L, 320L, 650L)) {
            main.postDelayed({
                if (!MirrorEngine.shouldOpenAutoKeyboard(pkg, displayId)) return@postDelayed
                val node = findFocusedEditableOnDisplays(pkg, displayId) ?: return@postDelayed
                requestKeyboard(pkg, displayId, node)
            }, delay)
        }
    }

    private fun requestKeyboard(pkg: String, displayId: Int, node: AccessibilityNodeInfo) {
        if (node.isPassword) return
        val text = node.text?.toString().orEmpty().take(300)
        KeyboardBridge.request(KeyboardBridge.Request(pkg, displayId, text))
    }

    private fun editableFromEventOrDisplay(
        event: AccessibilityEvent, pkg: String, displayId: Int
    ): AccessibilityNodeInfo? {
        event.source?.let { src ->
            if (src.packageName?.toString() == pkg && isEditable(src)) return src
        }
        return findFocusedEditableOnDisplays(pkg, displayId)
    }

    /** Tìm input focus trên đúng VirtualDisplay trước, rồi mới fallback sang display khác. */
    private fun findFocusedEditableOnDisplays(pkg: String, preferredDisplayId: Int): AccessibilityNodeInfo? {
        val all: SparseArray<List<AccessibilityWindowInfo>> = windowsOnAllDisplays

        if (preferredDisplayId >= 0) {
            findInWindows(all[preferredDisplayId], pkg)?.let { return it }
        }

        // Một số ROM báo event.displayId=0/-1 dù cửa sổ thực tế nằm trên VirtualDisplay.
        // Dò tất cả display nhưng vẫn khóa package theo pane vừa tap để không bật nhầm.
        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            if (displayId == preferredDisplayId) continue
            findInWindows(all.valueAt(i), pkg)?.let { return it }
        }
        return null
    }

    private fun findInWindows(
        windows: List<AccessibilityWindowInfo>?, pkg: String
    ): AccessibilityNodeInfo? {
        windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != pkg) continue

            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
                if (isEditable(it)) return it
            }

            findFocusedEditableDeep(root)?.let { return it }
        }
        return null
    }

    /** Fallback cho Compose/WebView/custom view khi findFocus() trả về container. */
    private fun findFocusedEditableDeep(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 1500) {
            val n = q.removeFirst()
            if ((n.isFocused || n.isAccessibilityFocused) && isEditable(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
        }
        return null
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false
        val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        return node.isEditable || supportsSetText
    }

    override fun onInterrupt() = Unit
}
