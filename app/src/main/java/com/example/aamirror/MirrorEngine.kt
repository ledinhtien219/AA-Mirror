package com.example.aamirror

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mỗi app được chọn chạy trên một VirtualDisplay riêng (kích thước đúng bằng vùng nó chiếm).
 * Khung hình của các VirtualDisplay được ghép lên một Surface duy nhất (màn hình xe hoặc preview).
 * Cảm ứng nhận từ Surface đó được quy đổi toạ độ rồi bơm vào đúng display qua Shizuku.
 */
object MirrorEngine {
    private const val TAG = "AAMirror"
    private const val GAP = 0
    private const val SCROLL_END_DELAY_MS = 160L
    private const val ANCHOR_TTL_MS = 6000L
    // Coalesce hai VirtualDisplay về ~30 fps tổng để giảm GPU/CPU và nhiệt.
    private const val FRAME_INTERVAL_MS = 33L

    // Cờ VirtualDisplay (một số là @hide nên khai báo số trực tiếp)
    private const val F_PUBLIC = 1
    private const val F_OWN_CONTENT_ONLY = 1 shl 3
    private const val F_SUPPORTS_TOUCH = 1 shl 6
    private const val F_ROTATES = 1 shl 7
    private const val F_DESTROY_ON_REMOVAL = 1 shl 8

    private class Pane(val pkg: String) {
        var rect = Rect()
        var dpi = 200
        var reader: ImageReader? = null
        var vd: VirtualDisplay? = null
        var image: Image? = null
        var bitmap: Bitmap? = null
        var launched = false

        // Mỗi pane tự nhớ điểm tương tác cuối của chính nó.
        // Không dùng chung anchor giữa app A/B để tránh vuốt app này lấy tọa độ của app kia.
        var lastTouchX = Float.NaN
        var lastTouchY = Float.NaN
        var lastTouchAt = 0L

        val displayId: Int get() = vd?.display?.displayId ?: -1

        fun release() {
            try { reader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
            try { vd?.release() } catch (_: Throwable) {}
            try { image?.close() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            image = null; bitmap = null; reader = null; vd = null
        }
    }

    private class Hit(val pane: Pane, val x: Float, val y: Float)
    private class Drag(val pane: Pane, var x: Float, var y: Float, val downTime: Long)

    private enum class Tool { APPS, RATIO, SWAP, BACK, KEYBOARD, REFRESH }
    private data class ToolButton(val tool: Tool, val rect: RectF, val iconRes: Int)

    private val thread = HandlerThread("aamirror-engine").apply { start() }
    private val handler = Handler(thread.looper)

    private var surface: Surface? = null
    private var sw = 0
    private var sh = 0
    private var showToolbarOnSurface = false
    private var panesPaused = false
    private var frameScheduled = false
    private var lastFrameAt = 0L
    private val panes = LinkedHashMap<String, Pane>()
    private var area: Rect? = null   // vùng nhìn thấy được (không bị thanh nút/launcher của xe che)

    // Snapshot đọc được từ AccessibilityService (thread khác).
    @Volatile private var displaySnapshot: Map<String, Int> = emptyMap()
    @Volatile private var lastCarTapPkg: String? = null
    @Volatile private var lastCarTapAt = 0L

    // Pane đang được điều khiển. Với Android Auto projected, onScroll chỉ có dx/dy
    // chứ không có tọa độ DOWN, vì vậy swipe phải thuộc về một pane active rõ ràng.
    private var focus: String? = null
    private var drag: Drag? = null
    private val endDrag = Runnable { finishDrag() }
    private var rawHit: Hit? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 36f }
    private val toolbarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 18, 18, 18)
        style = Paint.Style.FILL
    }
    private val toolbarButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 54, 54, 54)
        style = Paint.Style.FILL
    }

    private val renderFrame = Runnable {
        frameScheduled = false
        lastFrameAt = SystemClock.uptimeMillis()
        drawFrame()
    }

    // ---------------------------------------------------------------- vòng đời

    fun attach(s: Surface, w: Int, h: Int, showToolbar: Boolean = false) {
        handler.post {
            surface = s
            sw = w
            sh = h
            showToolbarOnSurface = showToolbar
            resumePanes()
            rebuild()
        }
    }

    /**
     * Không release VirtualDisplay khi host Surface biến mất. Nếu release display trong lúc task
     * còn sống, Android có thể chuyển task về display 0 rồi lần sau AA Mirror lại tạo thêm bản mới.
     * Giữ displayId/task và chỉ tháo render surface để giảm tải máy.
     */
    fun detach(s: Surface?) = detachInternal(s)
    fun detachKeepingPanes(s: Surface?) = detachInternal(s)

    private fun detachInternal(s: Surface?) {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (s == null || surface === s) {
                    surface = null
                    pausePanes()
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
    }

    /**
     * Car session destroy chỉ pause renderer. VirtualDisplay/task được giữ để reconnect không
     * nhân bản app và không migrate activity về màn hình điện thoại.
     */
    fun shutdown() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                surface = null
                pausePanes()
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
    }

    fun reconfigure() { handler.post { rebuild() } }

    fun setVisibleArea(r: Rect) {
        val copy = Rect(r)
        handler.post { area = copy; rebuild() }
    }

    fun refresh() {
        handler.post {
            // Soft refresh: reuse đúng VirtualDisplay/task hiện tại.
            panes.values.forEach { it.launched = false }
            launchPending()
            drawFrame()
        }
    }

    fun onShellReady() { handler.post { launchPending() } }

    private fun pausePanes() {
        if (panesPaused) return
        handler.removeCallbacks(renderFrame)
        frameScheduled = false
        for (p in panes.values) {
            try { p.vd?.surface = null } catch (_: Throwable) {}
            try { p.image?.close() } catch (_: Throwable) {}
            p.image = null
            p.bitmap = null
        }
        panesPaused = true
    }

    private fun resumePanes() {
        if (!panesPaused) return
        for (p in panes.values) {
            val r = p.reader ?: continue
            try { p.vd?.surface = r.surface } catch (t: Throwable) {
                Log.w(TAG, "resume VirtualDisplay " + p.pkg + " thất bại", t)
            }
        }
        panesPaused = false
    }

    private fun releaseAll() {
        handler.removeCallbacks(endDrag)
        handler.removeCallbacks(renderFrame)
        frameScheduled = false
        drag = null
        rawHit = null
        focus = null
        panes.values.forEach { it.release() }
        panes.clear()
        displaySnapshot = emptyMap()
    }

    // ---------------------------------------------------------------- bố cục

    private fun rebuild() {
        val s = surface
        if (s == null || !s.isValid || sw <= 0 || sh <= 0) {
            pausePanes()
            return
        }
        val cfg = Config.load()
        var pkgs = listOf(cfg.pkgA, cfg.pkgB).filter { it.isNotEmpty() }.distinct()
        if (cfg.mode == Mode.SINGLE) pkgs = pkgs.take(1)

        panes.keys.filter { it !in pkgs }.forEach { panes.remove(it)?.release() }

        val rects = computeRects(pkgs.size, cfg.mode)
        pkgs.forEachIndexed { i, pkg ->
            val rect = rects[i]
            val existing = panes[pkg]
            if (existing == null) {
                createPane(pkg, rect, cfg.dpi)?.let { panes[pkg] = it }
            } else if (existing.rect != rect || existing.dpi != cfg.dpi) {
                resizePane(existing, rect, cfg.dpi)
            }
        }

        // Giữ focus theo package khi đổi tỉ lệ/đổi chỗ; nếu app active đã bị bỏ thì chọn pane đầu.
        if (focus == null || !panes.containsKey(focus)) focus = panes.values.firstOrNull()?.pkg

        displaySnapshot = panes.values
            .filter { it.displayId >= 0 }
            .associate { it.pkg to it.displayId }

        launchPending()
        drawFrame()
    }

    private fun usableArea(): Rect {
        // Vẽ edge-to-edge trên toàn bộ Surface của Android Auto.
        // stableArea chỉ là vùng host khuyến nghị để tránh các nút overlay; nếu dùng nó để
        // tính kích thước VirtualDisplay sẽ tạo viền đen lớn quanh hai app như v0.3.0.
        // Các nút Android Auto có thể phủ lên nội dung, giống ứng dụng navigation thật.
        return Rect(0, 0, sw, sh)
    }

    private fun computeRects(count: Int, mode: Mode): List<Rect> {
        val a = usableArea()
        if (count <= 1) return listOf(a)
        val vertical = a.height() > a.width()
        val total = if (vertical) a.height() else a.width()
        val avail = total - GAP
        val first = (avail * mode.firstRatio).toInt().coerceIn(64, avail - 64)
        return if (vertical) listOf(
            Rect(a.left, a.top, a.right, a.top + first),
            Rect(a.left, a.top + first + GAP, a.right, a.bottom)
        ) else listOf(
            Rect(a.left, a.top, a.left + first, a.bottom),
            Rect(a.left + first + GAP, a.top, a.right, a.bottom)
        )
    }

    // ---------------------------------------------------------------- VirtualDisplay

    private fun newReader(p: Pane, w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        r.setOnImageAvailableListener({ reader -> onImage(p, reader) }, handler)
        return r
    }

    private fun createVd(pkg: String, w: Int, h: Int, dpi: Int, s: Surface): VirtualDisplay? {
        val dm = App.instance.getSystemService(DisplayManager::class.java)
        val full = F_PUBLIC or F_OWN_CONTENT_ONLY or F_SUPPORTS_TOUCH or F_ROTATES or F_DESTROY_ON_REMOVAL
        val minimal = F_PUBLIC or F_OWN_CONTENT_ONLY
        for (flags in intArrayOf(full, minimal)) {
            try {
                return dm.createVirtualDisplay("aamirror:$pkg", w, h, dpi, s, flags)
            } catch (t: Throwable) {
                Log.w(TAG, "createVirtualDisplay flags=$flags thất bại", t)
            }
        }
        return null
    }

    private fun createPane(pkg: String, rect: Rect, dpi: Int): Pane? {
        val p = Pane(pkg)
        p.rect = Rect(rect)
        p.dpi = dpi
        val reader = newReader(p, rect.width(), rect.height())
        p.reader = reader
        val vd = createVd(pkg, rect.width(), rect.height(), dpi, reader.surface)
        if (vd == null) {
            p.release()
            return null
        }
        p.vd = vd
        return p
    }

    private fun resizePane(p: Pane, rect: Rect, dpi: Int) {
        val oldReader = p.reader
        val oldImage = p.image
        val r = newReader(p, rect.width(), rect.height())
        p.reader = r
        p.image = null
        p.bitmap = null
        try {
            p.vd?.surface = r.surface
            p.vd?.resize(rect.width(), rect.height(), dpi)
        } catch (t: Throwable) {
            Log.w(TAG, "resize thất bại", t)
        }
        p.rect = Rect(rect)
        p.dpi = dpi
        try { oldReader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { oldImage?.close() } catch (_: Throwable) {}
        try { oldReader?.close() } catch (_: Throwable) {}
    }

    private fun launchPending() {
        val shell = ShizukuHelper.shell
        for (p in panes.values) {
            if (!p.launched && p.displayId >= 0) launch(p, shell)
        }
    }

    private fun launch(p: Pane, shell: IShellService?) {
        // Shizuku là yêu cầu bắt buộc. Không fallback Context.startActivity(): trên một số ROM
        // launchDisplayId bị bỏ qua và app bị mở nhầm thêm một bản trên display 0.
        shell ?: return
        val ctx = App.instance
        val intent = ctx.packageManager.getLaunchIntentForPackage(p.pkg) ?: return
        val comp = intent.component?.flattenToShortString() ?: return

        // Không dùng MULTIPLE_TASK. NEW_TASK|REORDER_TO_FRONT ưu tiên reuse task/activity hiện có.
        val ok = try {
            shell.exec("am start --display ${p.displayId} -f 0x10020000 -n '$comp'") == 0
        } catch (_: RemoteException) {
            false
        }
        if (!ok) Log.w(TAG, "launch " + p.pkg + " trên display " + p.displayId + " thất bại")
        p.launched = ok
    }

    // ---------------------------------------------------------------- vẽ

    private fun onImage(p: Pane, r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        if (p.reader !== r) { img.close(); return }
        val hb = img.hardwareBuffer
        val bmp = if (hb != null) Bitmap.wrapHardwareBuffer(hb, null) else null
        if (bmp == null) { img.close(); return }
        val old = p.image
        p.image = img
        p.bitmap = bmp
        requestFrame()
        try { old?.close() } catch (_: Throwable) {}
    }

    private fun requestFrame() {
        if (frameScheduled || surface == null || panesPaused) return
        val now = SystemClock.uptimeMillis()
        val delay = (FRAME_INTERVAL_MS - (now - lastFrameAt)).coerceAtLeast(0L)
        frameScheduled = true
        handler.postDelayed(renderFrame, delay)
    }

    private fun drawFrame() {
        lastFrameAt = SystemClock.uptimeMillis()
        val s = surface ?: return
        if (!s.isValid) return
        var hw = true
        var c: android.graphics.Canvas? = try {
            s.lockHardwareCanvas()
        } catch (t: Throwable) {
            Log.w(TAG, "lockHardwareCanvas thất bại, thử lockCanvas", t)
            hw = false
            null
        }
        if (c == null) {
            c = try { s.lockCanvas(null) } catch (t: Throwable) {
                Log.w(TAG, "lockCanvas thất bại", t)
                null
            }
        }
        if (c == null) return
        try {
            c.drawColor(Color.BLACK)
            for (p in panes.values) {
                val b = p.bitmap ?: continue
                val bb = if (hw) b else b.copy(Bitmap.Config.ARGB_8888, false)
                if (bb != null) c.drawBitmap(bb, null, p.rect, bitmapPaint)
            }
            if (panes.values.none { it.bitmap != null }) drawStatus(c)
            if (showToolbarOnSurface) drawToolbar(c)
        } finally {
            try { s.unlockCanvasAndPost(c) } catch (_: Throwable) {}
        }
    }

    private fun toolbarButtons(): List<ToolButton> {
        if (!showToolbarOnSurface || sw <= 0 || sh <= 0) return emptyList()

        val size = (sh * 0.095f).coerceIn(48f, 70f)
        val gap = (size * 0.14f).coerceIn(7f, 10f)
        val tools = arrayOf(
            Tool.APPS to R.drawable.ic_apps,
            Tool.RATIO to R.drawable.ic_ratio,
            Tool.SWAP to R.drawable.ic_swap,
            Tool.BACK to R.drawable.ic_back,
            Tool.KEYBOARD to R.drawable.ic_keyboard,
            Tool.REFRESH to R.drawable.ic_refresh
        )
        val total = tools.size * size + (tools.size - 1) * gap
        var x = (sw - total) / 2f
        val top = (sh * 0.018f).coerceIn(8f, 16f)

        return tools.map { (tool, icon) ->
            ToolButton(tool, RectF(x, top, x + size, top + size), icon).also {
                x += size + gap
            }
        }
    }

    /**
     * Toolbar riêng của AA Mirror được vẽ trực tiếp lên Surface, nên có thể đặt chính giữa
     * phía trên thay vì phụ thuộc vị trí ActionStrip do Android Auto host quyết định.
     */
    private fun drawToolbar(c: android.graphics.Canvas) {
        val buttons = toolbarButtons()
        if (buttons.isEmpty()) return

        val first = buttons.first().rect
        val last = buttons.last().rect
        val pad = (first.height() * 0.14f).coerceAtLeast(7f)
        val bg = RectF(first.left - pad, first.top - pad, last.right + pad, last.bottom + pad)
        c.drawRoundRect(bg, bg.height() / 2f, bg.height() / 2f, toolbarBgPaint)

        for (b in buttons) {
            val r = b.rect
            c.drawOval(r, toolbarButtonPaint)

            val inset = (r.width() * 0.27f).toInt()
            val d = App.instance.getDrawable(b.iconRes) ?: continue
            d.setBounds(
                r.left.toInt() + inset,
                r.top.toInt() + inset,
                r.right.toInt() - inset,
                r.bottom.toInt() - inset
            )
            d.draw(c)
        }
    }

    private fun handleToolbarClick(x: Float, y: Float): Boolean {
        val b = toolbarButtons().firstOrNull { it.rect.contains(x, y) } ?: return false
        when (b.tool) {
            Tool.APPS -> CarUiBridge.openAppPicker()
            Tool.RATIO -> {
                val c = Config.load()
                val next = when (c.mode) {
                    Mode.R30_70 -> Mode.R50_50
                    Mode.R50_50 -> Mode.R70_30
                    else -> Mode.R30_70
                }
                Config.save(c.copy(mode = next))
                rebuild()
            }
            Tool.SWAP -> {
                val c = Config.load()
                Config.save(c.copy(pkgA = c.pkgB, pkgB = c.pkgA))
                rebuild()
            }
            Tool.BACK -> backInternal()
            Tool.KEYBOARD -> {
                val p = focusPane()
                if (p != null && p.displayId >= 0) {
                    KeyboardBridge.request(
                        KeyboardBridge.Request(
                            pkg = p.pkg,
                            displayId = p.displayId,
                            initialText = "",
                            replaceExisting = false,
                            force = true
                        )
                    )
                }
            }
            Tool.REFRESH -> {
                panes.values.forEach { it.launched = false }
                launchPending()
            }
        }
        drawFrame()
        return true
    }

    /** Khi chưa có hình, hiện trạng thái ngay trên màn hình xe để biết đang kẹt ở đâu. */
    private fun drawStatus(c: android.graphics.Canvas) {
        val a = usableArea()
        val lines = ArrayList<String>()
        lines.add("AA Mirror - bề mặt ${sw}x${sh}, vùng dùng được ${a.width()}x${a.height()}")
        if (panes.isEmpty()) lines.add("Chưa có app: mở AA Mirror trên điện thoại và chọn app A / B")
        lines.add(
            if (ShizukuHelper.shell != null) "Shizuku: sẵn sàng"
            else "Shizuku: CHƯA kết nối (cấp quyền trong app trên điện thoại)"
        )
        for (p in panes.values) {
            lines.add(
                "${p.pkg}: display ${p.displayId}, " +
                    (if (p.launched) "đã mở" else "chưa mở") + ", " +
                    (if (p.bitmap != null) "có hình" else "chưa có hình")
            )
        }
        var y = a.top + 70f
        for (l in lines) {
            c.drawText(l, a.left + 24f, y, textPaint)
            y += 48f
        }
    }

    // ---------------------------------------------------------------- cảm ứng

    private fun hit(x: Float, y: Float): Hit? {
        for (p in panes.values) {
            if (p.rect.contains(x.toInt(), y.toInt())) return Hit(p, x - p.rect.left, y - p.rect.top)
        }
        return null
    }

    private fun focusPane(): Pane? {
        focus?.let { panes[it] }?.let { return it }
        return panes.values.firstOrNull()?.also { focus = it.pkg }
    }

    private fun setFocus(p: Pane) {
        // Focus vẫn được giữ nội bộ để route swipe/Back/keyboard đúng pane,
        // nhưng không vẽ viền màu lên UI nữa.
        focus = p.pkg
    }

    private fun rememberTouch(p: Pane, x: Float, y: Float, at: Long = SystemClock.uptimeMillis()) {
        p.lastTouchX = x.coerceIn(0f, (p.rect.width() - 1).coerceAtLeast(0).toFloat())
        p.lastTouchY = y.coerceIn(0f, (p.rect.height() - 1).coerceAtLeast(0).toFloat())
        p.lastTouchAt = at
    }

    private fun anchorFor(p: Pane): Pair<Float, Float> {
        val now = SystemClock.uptimeMillis()
        val fresh = now - p.lastTouchAt <= ANCHOR_TTL_MS &&
            !p.lastTouchX.isNaN() && !p.lastTouchY.isNaN()
        return if (fresh) {
            p.lastTouchX to p.lastTouchY
        } else {
            p.rect.width() / 2f to p.rect.height() / 2f
        }
    }

    private fun inject(p: Pane, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val shell = ShizukuHelper.shell ?: return
        val id = p.displayId
        if (id < 0) return
        try { shell.injectMotion(id, action, x, y, downTime, eventTime) } catch (_: RemoteException) {}
    }

    /**
     * AccessibilityService gọi hàm này để xác nhận event editable thực sự đến từ pane vừa
     * được người dùng chạm trên Android Auto. Một số ROM báo displayId=0/-1 cho event từ
     * VirtualDisplay, nên recent-tap là điều kiện bắt buộc và cũng là lớp chống false-positive.
     */
    fun shouldOpenAutoKeyboard(pkg: String, eventDisplayId: Int): Boolean {
        val expectedDisplay = displaySnapshot[pkg] ?: return false
        val age = SystemClock.uptimeMillis() - lastCarTapAt
        if (lastCarTapPkg != pkg || age !in 0L..2500L) return false
        return eventDisplayId == expectedDisplay || eventDisplayId <= 0
    }

    /**
     * Chạm từ màn hình xe: onClick có tọa độ nên route chính xác vào pane nằm dưới ngón tay.
     * Chạm vào pane nào thì pane đó trở thành active cho Back / keyboard / swipe tiếp theo.
     */
    fun click(x: Float, y: Float) {
        handler.post {
            handler.removeCallbacks(endDrag)
            finishDrag()

            // Toolbar nằm trên Surface nên phải consume trước khi route tap xuống app.
            if (handleToolbarClick(x, y)) return@post

            val h = hit(x, y) ?: return@post
            setFocus(h.pane)
            val t = SystemClock.uptimeMillis()
            lastCarTapPkg = h.pane.pkg
            lastCarTapAt = t
            rememberTouch(h.pane, h.x, h.y, t)
            inject(h.pane, MotionEvent.ACTION_DOWN, h.x, h.y, t, t)
            handler.postDelayed({
                inject(h.pane, MotionEvent.ACTION_UP, h.x, h.y, t, SystemClock.uptimeMillis())
                // Không phụ thuộc hoàn toàn vào TYPE_VIEW_FOCUSED: sau mỗi tap, Accessibility
                // chủ động dò focused editable trên đúng VirtualDisplay. Điều này cần cho
                // YouTube/Compose/WebView, nơi event.source thường chỉ là container.
                MirrorAccessibilityService.probeAfterCarTap(h.pane.pkg, h.pane.displayId)
                probeImeEditorAfterTap(h.pane)
            }, 50)
        }
    }

    /**
     * Fallback độc lập với AccessibilityService.
     *
     * Khi một EditText/Search box nhận focus, InputMethodManager vẫn tạo EditorInfo cho package
     * đích ngay cả khi IME không thể vẽ lên VirtualDisplay app-owned. Shell/Shizuku đọc trạng thái
     * này từ dumpsys input_method; nếu đúng package vừa tap, mở SearchTemplate của Android Auto.
     * Cách này bắt được YouTube/Maps/Compose/WebView trên các ROM không expose VirtualDisplay qua
     * AccessibilityService.
     */
    private fun probeImeEditorAfterTap(p: Pane) {
        for (delay in longArrayOf(160L, 420L, 850L)) {
            handler.postDelayed({
                val age = SystemClock.uptimeMillis() - lastCarTapAt
                if (lastCarTapPkg != p.pkg || age !in 0L..2500L) return@postDelayed
                val shell = ShizukuHelper.shell ?: return@postDelayed
                val out = try {
                    // Chỉ đọc các dòng EditorInfo thực sự. Tuyệt đối không dùng
                    // mCurFocusedWindow/packageName chung vì chúng tồn tại cả khi người dùng
                    // chỉ tap vào một view bình thường và sẽ làm keyboard bật sai.
                    shell.execForOutput(
                        "dumpsys input_method | grep -E " +
                            "'mCurAttribute=EditorInfo|mCurrentTextBoxAttribute=EditorInfo' | head -n 20"
                    )
                } catch (_: RemoteException) {
                    ""
                }
                if (editorStateBelongsTo(out, p.pkg)) {
                    KeyboardBridge.request(
                        KeyboardBridge.Request(
                            pkg = p.pkg,
                            displayId = p.displayId,
                            initialText = ""
                        )
                    )
                }
            }, delay)
        }
    }

    private fun editorStateBelongsTo(out: String, pkg: String): Boolean {
        if (out.isBlank()) return false
        // dumpsys phải chứa EditorInfo + đúng package. Nếu không có EditorInfo thì đây
        // không phải text editor và không được phép mở keyboard.
        val hasEditor = out.contains("EditorInfo")
        val hasPkg = out.contains("packageName=$pkg") || out.contains("packageName: $pkg")
        return hasEditor && hasPkg
    }

    /**
     * Vuốt từ Android Auto projected. SurfaceCallback.onScroll chỉ trả dx/dy, KHÔNG trả tọa độ
     * nơi ngón tay bắt đầu. Vì vậy mỗi gesture được khóa cứng vào pane đang active ở thời điểm
     * scroll bắt đầu; trong suốt gesture tuyệt đối không chuyển sang pane còn lại.
     *
     * Mỗi pane có anchor riêng nên A/B không dùng lẫn điểm chạm cuối của nhau.
     */
    fun scroll(dx: Float, dy: Float) {
        handler.post {
            var d = drag
            if (d == null) {
                val p = focusPane() ?: return@post
                val (sx, sy) = anchorFor(p)
                val t = SystemClock.uptimeMillis()
                d = Drag(p, sx, sy, t)
                drag = d
                inject(p, MotionEvent.ACTION_DOWN, sx, sy, t, t)
            }

            // d.pane là owner bất biến của gesture này.
            d.x = (d.x - dx).coerceIn(0f, d.pane.rect.width() - 1f)
            d.y = (d.y - dy).coerceIn(0f, d.pane.rect.height() - 1f)
            val now = SystemClock.uptimeMillis()
            rememberTouch(d.pane, d.x, d.y, now)
            inject(d.pane, MotionEvent.ACTION_MOVE, d.x, d.y, d.downTime, now)
            handler.removeCallbacks(endDrag)
            handler.postDelayed(endDrag, SCROLL_END_DELAY_MS)
        }
    }

    fun fling() {
        handler.post {
            handler.removeCallbacks(endDrag)
            finishDrag()
        }
    }

    private fun finishDrag() {
        val d = drag ?: return
        drag = null
        val now = SystemClock.uptimeMillis()
        rememberTouch(d.pane, d.x, d.y, now)
        inject(d.pane, MotionEvent.ACTION_UP, d.x, d.y, d.downTime, now)
    }

    /**
     * Preview trên điện thoại có raw DOWN/MOVE/UP nên route chuẩn theo vị trí ACTION_DOWN.
     * Dù ngón tay kéo qua đường chia sang pane khác, gesture vẫn thuộc pane đã nhận DOWN.
     */
    fun rawTouch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        handler.post {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(endDrag)
                    finishDrag()
                    val h = hit(x, y) ?: return@post
                    rawHit = h
                    setFocus(h.pane)
                    rememberTouch(h.pane, h.x, h.y, eventTime)
                    inject(h.pane, MotionEvent.ACTION_DOWN, h.x, h.y, downTime, eventTime)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val h = rawHit ?: return@post
                    val lx = (x - h.pane.rect.left).coerceIn(0f, h.pane.rect.width() - 1f)
                    val ly = (y - h.pane.rect.top).coerceIn(0f, h.pane.rect.height() - 1f)
                    rememberTouch(h.pane, lx, ly, eventTime)
                    val a = if (action == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_UP else action
                    inject(h.pane, a, lx, ly, downTime, eventTime)
                    if (action != MotionEvent.ACTION_MOVE) rawHit = null
                }
            }
        }
    }

    /**
     * Gõ chữ vào ô đang focus của pane active. Nút keyboard thủ công dùng đường này.
     */
    fun typeText(text: String, enter: Boolean) = typeTextTo(null, text, enter, replace = false)

    /**
     * Gõ vào một package cụ thể. Auto keyboard dùng targetPkg để khoá đích vào đúng app đã
     * phát sinh ô nhập, không phụ thuộc focus có thay đổi sau đó hay không.
     *
     * replace=true: Ctrl+A -> Delete trước khi nhập, vì SearchTemplate trả về toàn bộ chuỗi.
     */
    fun typeTextTo(targetPkg: String?, text: String, enter: Boolean, replace: Boolean) {
        handler.post {
            val p = if (targetPkg != null) panes[targetPkg] else focusPane()
            p ?: return@post
            val shell = ShizukuHelper.shell ?: return@post
            val id = p.displayId
            if (id < 0 || text.isEmpty() && !enter) return@post
            try {
                if (replace) {
                    // Android 'input' hỗ trợ keycombination và -d DISPLAY_ID.
                    shell.exec("input -d $id keycombination KEYCODE_CTRL_LEFT KEYCODE_A")
                    Thread.sleep(35)
                    shell.injectKey(id, KeyEvent.KEYCODE_DEL)
                    Thread.sleep(35)
                }

                if (text.isNotEmpty()) {
                    val ascii = text.all { it.code in 32..126 && it != '%' }
                    if (ascii) {
                        val esc = text.replace("'", "'\\''").replace(" ", "%s")
                        shell.exec("input -d $id text '$esc'")
                    } else {
                        val cm = App.instance.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("aamirror", text))
                        shell.injectKey(id, KeyEvent.KEYCODE_PASTE)
                    }
                }
                if (enter) {
                    Thread.sleep(120)
                    shell.injectKey(id, KeyEvent.KEYCODE_ENTER)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun backInternal() {
        val p = focusPane() ?: return
        val shell = ShizukuHelper.shell ?: return
        try { shell.injectKey(p.displayId, KeyEvent.KEYCODE_BACK) } catch (_: RemoteException) {}
    }

    fun back() {
        handler.post { backInternal() }
    }
}
