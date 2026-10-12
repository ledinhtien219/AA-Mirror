package com.example.aamirror

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
    private const val GAP = 8
    private const val SCROLL_END_DELAY_MS = 160L
    private const val ANCHOR_TTL_MS = 6000L

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

    private val thread = HandlerThread("aamirror-engine").apply { start() }
    private val handler = Handler(thread.looper)

    private var surface: Surface? = null
    private var sw = 0
    private var sh = 0
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
    private val paneBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val focusBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 36f }

    // ---------------------------------------------------------------- vòng đời

    fun attach(s: Surface, w: Int, h: Int) {
        handler.post {
            surface = s; sw = w; sh = h
            rebuild()
        }
    }

    /** Gọi khi Surface bị huỷ và không cần giữ session (preview/thoát hẳn). */
    fun detach(s: Surface?) = detachInternal(s, keepPanes = false)

    /**
     * Android Auto có thể thu hồi Surface tạm thời khi push SearchTemplate để hiện keyboard.
     * Trường hợp đó phải giữ VirtualDisplay/app sống, nếu không Submit xong sẽ không còn đích để gõ.
     */
    fun detachKeepingPanes(s: Surface?) = detachInternal(s, keepPanes = true)

    private fun detachInternal(s: Surface?, keepPanes: Boolean) {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (s == null || surface === s) {
                    if (!keepPanes) releaseAll()
                    surface = null
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
    }

    /** Kết thúc hẳn Car session và giải phóng VirtualDisplay. */
    fun shutdown() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                releaseAll()
                surface = null
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

    fun refresh() { handler.post { releaseAll(); rebuild() } }

    fun onShellReady() { handler.post { launchPending() } }

    private fun releaseAll() {
        handler.removeCallbacks(endDrag)
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
            releaseAll()
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
        val full = Rect(0, 0, sw, sh)
        val v = area ?: return full
        val r = Rect(v)
        if (!r.intersect(full) || r.width() < 200 || r.height() < 100) return full
        return r
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
            w, h, PixelFormat.RGBA_8888, 3, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
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
        val ctx = App.instance
        val intent = ctx.packageManager.getLaunchIntentForPackage(p.pkg) ?: return
        val comp = intent.component?.flattenToShortString() ?: return
        var ok = false
        if (shell != null) {
            ok = try {
                shell.exec("am start --display ${p.displayId} -f 0x18000000 -n '$comp'") == 0
            } catch (_: RemoteException) { false }
        }
        if (!ok) {
            ok = try {
                val opts = ActivityOptions.makeBasic().setLaunchDisplayId(p.displayId)
                val i = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                ctx.startActivity(i, opts.toBundle())
                true
            } catch (t: Throwable) {
                Log.w(TAG, "launch ${p.pkg} thất bại", t)
                false
            }
        }
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
        drawFrame()
        try { old?.close() } catch (_: Throwable) {}
    }

    private fun drawFrame() {
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
            drawPaneChrome(c)
            if (panes.values.none { it.bitmap != null }) drawStatus(c)
        } finally {
            try { s.unlockCanvasAndPost(c) } catch (_: Throwable) {}
        }
    }

    /** Viền mảnh cho từng app + viền nổi bật pane đang nhận Back/keyboard/swipe. */
    private fun drawPaneChrome(c: android.graphics.Canvas) {
        if (panes.size <= 1) return
        for (p in panes.values) {
            val r = RectF(p.rect)
            r.inset(1.5f, 1.5f)
            c.drawRect(r, paneBorderPaint)
        }
        val active = focus?.let { panes[it] } ?: return
        val r = RectF(active.rect)
        r.inset(3f, 3f)
        c.drawRect(r, focusBorderPaint)
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
        val changed = focus != p.pkg
        focus = p.pkg
        if (changed) drawFrame()
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

            val h = hit(x, y) ?: return@post
            setFocus(h.pane)
            val t = SystemClock.uptimeMillis()
            lastCarTapPkg = h.pane.pkg
            lastCarTapAt = t
            rememberTouch(h.pane, h.x, h.y, t)
            inject(h.pane, MotionEvent.ACTION_DOWN, h.x, h.y, t, t)
            handler.postDelayed({
                inject(h.pane, MotionEvent.ACTION_UP, h.x, h.y, t, SystemClock.uptimeMillis())
            }, 50)
        }
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

    fun back() {
        handler.post {
            val p = focusPane() ?: return@post
            val shell = ShizukuHelper.shell ?: return@post
            try { shell.injectKey(p.displayId, KeyEvent.KEYCODE_BACK) } catch (_: RemoteException) {}
        }
    }
}
