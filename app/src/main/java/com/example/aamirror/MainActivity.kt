package com.example.aamirror

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private var cfg = Config.load()
    private lateinit var status: TextView
    private lateinit var btnShizuku: Button
    private lateinit var btnPhoneCapture: Button
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnAutoKeyboard: Button
    private lateinit var dpiText: TextView
    private val modeIds = LinkedHashMap<Int, Mode>()
    private val listener: () -> Unit = { runOnUiThread { refreshStatus() } }
    private var updateChecked = false

    private companion object {
        const val REQ_PHONE_CAPTURE = 4200
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float = 14f) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            ins
        }
        setContentView(scroll)

        root.addView(tv("AA Mirror", 24f))
        status = tv("")
        root.addView(status)
        btnShizuku = btn("Kết nối Shizuku") { onShizukuClick() }
        root.addView(btnShizuku)

        root.addView(tv("0. Không Shizuku / không Wireless Debugging", 18f))
        btnPhoneCapture = btn("") {
            if (PhoneCaptureState.isActive()) {
                PhoneCaptureService.stop(this)
                btnPhoneCapture.text = "Đang dừng..."
            } else {
                startPhoneCaptureFlow()
            }
        }
        root.addView(btnPhoneCapture)
        root.addView(tv(
            "Chế độ này dùng split-screen thật trên điện thoại + MediaProjection + Trợ năng. " +
                "Lần bắt đầu Android sẽ hỏi quyền chia sẻ màn hình; hãy chọn toàn bộ màn hình. " +
                "Không cần bật Wireless Debugging.", 13f
        ))

        root.addView(tv("1. Chọn app", 18f))
        btnA = btn("") { pickApp(true) }
        btnB = btn("") { pickApp(false) }
        root.addView(btnA)
        root.addView(btnB)

        root.addView(tv("2. Tỉ lệ hiển thị", 18f))
        val group = RadioGroup(this)
        for (m in Mode.values()) {
            val rb = RadioButton(this).apply { text = m.label; id = View.generateViewId() }
            modeIds[rb.id] = m
            group.addView(rb)
            if (m == cfg.mode) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { _, id ->
            modeIds[id]?.let { cfg = cfg.copy(mode = it); commit() }
        }
        root.addView(group)

        root.addView(tv("3. Mật độ điểm ảnh (DPI) của app trên xe", 18f))
        dpiText = tv("")
        root.addView(dpiText)
        val seek = SeekBar(this).apply {
            max = 280                       // 120..400
            progress = cfg.dpi - 120
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    dpiText.text = "${p + 120} dpi (nhỏ = nhiều nội dung, lớn = chữ to)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    cfg = cfg.copy(dpi = (s?.progress ?: 80) + 120); commit()
                }
            })
        }
        dpiText.text = "${cfg.dpi} dpi (nhỏ = nhiều nội dung, lớn = chữ to)"
        root.addView(seek)

        root.addView(tv("4. Chạy thử", 18f))
        root.addView(btn("Xem thử trên điện thoại (không cần xe)") {
            startActivity(Intent(this, PreviewActivity::class.java))
        })
        btnAutoKeyboard = btn("") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        root.addView(btnAutoKeyboard)
        root.addView(tv(
            "Trợ năng giờ có thể dùng như chế độ điều khiển cơ bản khi không bật Shizuku: " +
                "click node, cuộn danh sách và nhập text trên đúng VirtualDisplay. Shizuku vẫn cần " +
                "cho raw touch/drag như kéo bản đồ, slider hoặc giao diện custom.", 13f
        ))

        root.addView(tv(
            "Trên xe: bật Developer settings của Android Auto → Unknown sources, " +
            "rồi mở \"AA Mirror\" trong danh sách app. Toolbar giữa phía trên có: " +
            "chọn app / đổi tỉ lệ / đổi chỗ / Back / bàn phím / tải lại.", 13f
        ))

        root.addView(tv("5. Cập nhật", 18f))
        root.addView(btn("Kiểm tra bản mới") {
            UpdateChecker.checkInApp(this, force = true)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }

        refreshButtons()
        refreshAutoKeyboard()
        refreshPhoneCapture()
    }

    override fun onResume() {
        super.onResume()
        ShizukuHelper.addListener(listener)
        ShizukuHelper.tryBind()
        refreshStatus()
        refreshAutoKeyboard()
        refreshPhoneCapture()
        if (!updateChecked) {
            updateChecked = true
            UpdateChecker.checkInApp(this, force = false)
        }
    }

    override fun onPause() {
        ShizukuHelper.removeListener(listener)
        super.onPause()
    }

    private fun commit() {
        Config.save(cfg)
        MirrorEngine.reconfigure()
        refreshButtons()
    }

    private fun label(pkg: String): String {
        if (pkg.isEmpty()) return "(chưa chọn)"
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }
    }

    private fun refreshButtons() {
        btnA.text = "App A: ${label(cfg.pkgA)}"
        btnB.text = "App B: ${label(cfg.pkgB)}"
    }

    private fun autoKeyboardEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = ComponentName(this, MirrorAccessibilityService::class.java)
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == target }
    }

    private fun refreshPhoneCapture() {
        if (!::btnPhoneCapture.isInitialized) return
        btnPhoneCapture.text = if (PhoneCaptureState.isActive())
            "Phone Split Capture: ĐANG BẬT ✔ — bấm để dừng"
        else
            "Bắt đầu Phone Split Capture (không Shizuku)"
    }

    private fun startPhoneCaptureFlow() {
        if (!autoKeyboardEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Cần bật Trợ năng AA Mirror")
                .setMessage(
                    "Chế độ không Shizuku cần Trợ năng để tạo split-screen và điều khiển hai app."
                )
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Mở Trợ năng") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
            return
        }

        val pm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            pm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQ_PHONE_CAPTURE)
    }

    @Deprecated("Legacy result API is sufficient for MediaProjection token handoff")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHONE_CAPTURE || resultCode != RESULT_OK || data == null) return

        val i = Intent(this, PhoneCaptureService::class.java)
            .putExtra(PhoneCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(PhoneCaptureService.EXTRA_RESULT_DATA, data)
        startForegroundService(i)
        btnPhoneCapture.text = "Đang khởi động Phone Split Capture..."
    }

    private fun refreshAutoKeyboard() {
        if (!::btnAutoKeyboard.isInitialized) return
        btnAutoKeyboard.text = if (autoKeyboardEnabled())
            "Tự bật bàn phím khi chạm ô nhập: BẬT ✔"
        else
            "Tự bật bàn phím khi chạm ô nhập: TẮT — bấm để bật"
    }

    private fun refreshStatus() {
        val alive = ShizukuHelper.binderAlive()
        val ok = ShizukuHelper.permitted()
        val ready = ShizukuHelper.shell != null
        val a11y = autoKeyboardEnabled()
        status.text = when {
            ready -> "Sẵn sàng ✔ Chế độ đầy đủ: Shizuku + Trợ năng"
            a11y -> "Sẵn sàng ✔ Chế độ Trợ năng: click / scroll / text. Raw drag cần Shizuku."
            !alive -> "Chưa có quyền điều khiển. Bật Trợ năng AA Mirror để dùng chế độ cơ bản, hoặc chạy Shizuku để điều khiển đầy đủ."
            !ok -> "Shizuku chưa cấp quyền. Bạn vẫn có thể bật Trợ năng AA Mirror để thử chế độ không Shizuku."
            else -> "Đang kết nối Shizuku... Hoặc bật Trợ năng AA Mirror để dùng chế độ cơ bản."
        }
        btnShizuku.text = when {
            !alive -> "Mở Shizuku"
            !ok -> "Cấp quyền Shizuku"
            else -> "Kết nối lại dịch vụ"
        }
    }

    private fun onShizukuClick() {
        when {
            !ShizukuHelper.binderAlive() -> {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i != null) startActivity(i)
                else status.text = "Chưa cài Shizuku. Cài từ GitHub/Play Store (moe.shizuku.privileged.api)."
            }
            !ShizukuHelper.permitted() -> ShizukuHelper.requestPermission()
            else -> ShizukuHelper.tryBind()
        }
        refreshStatus()
    }

    @Suppress("DEPRECATION")
    private fun pickApp(isA: Boolean) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val names = (listOf("(Không chọn)") + apps.map { it.second }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (isA) "Chọn app A (bên trái/trên)" else "Chọn app B (bên phải/dưới)")
            .setItems(names) { _, which ->
                val pkg = if (which == 0) "" else apps[which - 1].first
                cfg = if (isA) cfg.copy(pkgA = pkg) else cfg.copy(pkgB = pkg)
                commit()
                if (PhoneCaptureState.isActive()) {
                    MirrorAccessibilityService.startPhoneSplit(cfg.pkgA, cfg.pkgB)
                }
            }
            .show()
    }
}
