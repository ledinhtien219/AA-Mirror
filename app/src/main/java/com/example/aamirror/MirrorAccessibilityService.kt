package com.example.aamirror

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Nhận biết người dùng vừa chạm vào một ô nhập liệu trên VirtualDisplay.
 * Không đọc/khởi tạo keyboard cho password. Khi thấy editable focus, service yêu cầu
 * CarApp mở SearchTemplate để Android Auto hiển thị keyboard native.
 */
class MirrorAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return

        val pkg = event.packageName?.toString() ?: return

        // Chỉ phản ứng nếu ngay trước đó có tap thật từ màn hình Android Auto vào đúng pane.
        // Một vài ROM trả displayId 0/-1 cho Accessibility event của VirtualDisplay, nên
        // MirrorEngine còn dùng recent-tap + package để chống bật nhầm.
        if (!MirrorEngine.shouldOpenAutoKeyboard(pkg, event.displayId)) return

        val node = findEditableNode(event, pkg) ?: return
        if (node.isPassword) return

        val text = node.text?.toString().orEmpty().take(300)
        KeyboardBridge.request(KeyboardBridge.Request(pkg, event.displayId, text))
    }

    /**
     * event.source đôi khi là container của search box (Compose/WebView/custom view).
     * Nếu source chưa editable, tìm input-focus trong window cùng package làm fallback.
     */
    private fun findEditableNode(event: AccessibilityEvent, pkg: String): AccessibilityNodeInfo? {
        event.source?.let { if (isEditable(it)) return it }

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != pkg) continue
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (isEditable(focused)) return focused
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
