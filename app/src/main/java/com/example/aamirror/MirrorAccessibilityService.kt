package com.example.aamirror

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * Hybrid Accessibility controller.
 *
 * Khi có Shizuku, MirrorEngine vẫn dùng raw input. Khi không có Shizuku, service này thao tác
 * trực tiếp AccessibilityNodeInfo trên đúng VirtualDisplay: click node, scroll container, setText.
 */
class MirrorAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: MirrorAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        fun probeAfterCarTap(pkg: String, displayId: Int) {
            instance?.scheduleProbe(pkg, displayId)
        }

        fun clickAt(pkg: String, displayId: Int, x: Float, y: Float) {
            instance?.main?.post { instance?.clickAtInternal(pkg, displayId, x, y) }
        }

        fun scrollAt(
            pkg: String, displayId: Int, x: Float, y: Float, dx: Float, dy: Float
        ) {
            instance?.main?.post { instance?.scrollAtInternal(pkg, displayId, x, y, dx, dy) }
        }

        fun setText(
            pkg: String, displayId: Int, text: String, submit: Boolean, replace: Boolean
        ) {
            instance?.main?.post {
                instance?.setTextInternal(pkg, displayId, text, submit, replace)
            }
        }

        fun back(pkg: String, displayId: Int) {
            instance?.main?.post {
                // performGlobalAction không có displayId. Chỉ gọi khi window của package đích
                // thực sự đang tồn tại trên display đó, tránh Back nhầm màn hình điện thoại.
                val svc = instance ?: return@post
                if (svc.hasPackageWindow(pkg, displayId)) {
                    svc.performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MirrorEngine.onShellReady() // trigger launchPending cho Accessibility-only mode
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
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

    private fun windowsFor(displayId: Int): List<AccessibilityWindowInfo> {
        val all: SparseArray<List<AccessibilityWindowInfo>> = windowsOnAllDisplays
        if (displayId >= 0) all[displayId]?.let { return it }
        return emptyList()
    }

    private fun packageRoots(pkg: String, displayId: Int): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        for (w in windowsFor(displayId)) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == pkg) result.add(root)
        }
        return result
    }

    private fun hasPackageWindow(pkg: String, displayId: Int): Boolean =
        packageRoots(pkg, displayId).isNotEmpty()

    private fun supports(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private data class NodeCandidate(
        val node: AccessibilityNodeInfo,
        val area: Long,
        val depth: Int
    )

    /**
     * Chọn node actionable nhỏ nhất chứa điểm chạm. Nếu text/icon con không clickable,
     * clickable parent thường cũng chứa cùng điểm và sẽ được chọn.
     */
    private fun actionableAt(
        pkg: String, displayId: Int, x: Float, y: Float
    ): AccessibilityNodeInfo? {
        var best: NodeCandidate? = null
        for (root in packageRoots(pkg, displayId)) {
            val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            q.add(root to 0)
            var seen = 0
            while (q.isNotEmpty() && seen++ < 2500) {
                val (n, depth) = q.removeFirst()
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.contains(x.toInt(), y.toInt())) {
                    val actionable = n.isClickable || n.isEditable ||
                        supports(n, AccessibilityNodeInfo.ACTION_CLICK) ||
                        supports(n, AccessibilityNodeInfo.ACTION_SET_TEXT)
                    if (actionable) {
                        val area = b.width().toLong().coerceAtLeast(1) *
                            b.height().toLong().coerceAtLeast(1)
                        val c = NodeCandidate(n, area, depth)
                        val prev = best
                        if (prev == null || c.area < prev.area ||
                            c.area == prev.area && c.depth > prev.depth
                        ) best = c
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { q.add(it to depth + 1) }
                    }
                }
            }
        }
        return best?.node
    }

    private fun clickAtInternal(pkg: String, displayId: Int, x: Float, y: Float) {
        val node = actionableAt(pkg, displayId, x, y) ?: return
        val editable = isEditable(node)
        if (editable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (editable) {
            scheduleProbe(pkg, displayId)
        }
    }

    private fun scrollableAt(
        pkg: String, displayId: Int, x: Float, y: Float
    ): AccessibilityNodeInfo? {
        var best: NodeCandidate? = null
        var fallback: NodeCandidate? = null
        for (root in packageRoots(pkg, displayId)) {
            val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            q.add(root to 0)
            var seen = 0
            while (q.isNotEmpty() && seen++ < 2500) {
                val (n, depth) = q.removeFirst()
                val scrollable = n.isScrollable ||
                    supports(n, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                    supports(n, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (scrollable) {
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    val area = b.width().toLong().coerceAtLeast(1) *
                        b.height().toLong().coerceAtLeast(1)
                    val c = NodeCandidate(n, area, depth)
                    if (fallback == null || c.area < fallback!!.area) fallback = c
                    if (b.contains(x.toInt(), y.toInt())) {
                        val prev = best
                        if (prev == null || c.area < prev.area ||
                            c.area == prev.area && c.depth > prev.depth
                        ) best = c
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it to depth + 1) }
            }
        }
        return (best ?: fallback)?.node
    }

    private fun scrollAtInternal(
        pkg: String, displayId: Int, x: Float, y: Float, dx: Float, dy: Float
    ) {
        if (abs(dx) < 2f && abs(dy) < 2f) return
        val node = scrollableAt(pkg, displayId, x, y) ?: return

        val action = if (abs(dy) >= abs(dx)) {
            if (dy > 0) {
                if (supports(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id))
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                if (supports(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id))
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
        } else {
            if (dx > 0) {
                if (supports(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id))
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                if (supports(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id))
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
        }
        node.performAction(action)
    }

    private fun setTextInternal(
        pkg: String, displayId: Int, text: String, submit: Boolean, replace: Boolean
    ) {
        val node = findFocusedEditableOnDisplays(pkg, displayId) ?: return
        if (node.isPassword) return

        val value = if (replace) text else {
            val old = node.text?.toString().orEmpty()
            if (old.isEmpty()) text else old + text
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (submit) {
            main.postDelayed({
                val refreshed = findFocusedEditableOnDisplays(pkg, displayId) ?: node
                if (supports(
                        refreshed,
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                ) {
                    refreshed.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                }
            }, 80)
        }
    }

    private fun scheduleProbe(pkg: String, displayId: Int) {
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

    private fun findFocusedEditableOnDisplays(
        pkg: String, preferredDisplayId: Int
    ): AccessibilityNodeInfo? {
        val all: SparseArray<List<AccessibilityWindowInfo>> = windowsOnAllDisplays

        if (preferredDisplayId >= 0) {
            findInWindows(all[preferredDisplayId], pkg)?.let { return it }
        }

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

    private fun findFocusedEditableDeep(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 2500) {
            val n = q.removeFirst()
            if ((n.isFocused || n.isAccessibilityFocused) && isEditable(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
        }
        return null
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false
        return node.isEditable || supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
    }

    override fun onInterrupt() = Unit
}
