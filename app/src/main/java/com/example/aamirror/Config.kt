package com.example.aamirror

import android.content.Context

enum class Mode(val label: String, val firstRatio: Float) {
    SINGLE("Một app (toàn màn hình)", 1f),
    R30_70("30 - 70", 0.3f),
    R50_50("50 - 50", 0.5f),
    R70_30("70 - 30", 0.7f)
}

data class Cfg(val pkgA: String, val pkgB: String, val mode: Mode, val dpi: Int)

object Config {
    private fun sp() = App.instance.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun load(): Cfg {
        val p = sp()
        val mode = runCatching { Mode.valueOf(p.getString("mode", Mode.R50_50.name)!!) }
            .getOrDefault(Mode.R50_50)
        return Cfg(p.getString("a", "")!!, p.getString("b", "")!!, mode, p.getInt("dpi", 200))
    }

    fun save(c: Cfg) {
        sp().edit()
            .putString("a", c.pkgA)
            .putString("b", c.pkgB)
            .putString("mode", c.mode.name)
            .putInt("dpi", c.dpi)
            .apply()
    }
}
