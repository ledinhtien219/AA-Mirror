package com.example.aamirror

import android.os.Handler
import android.os.Looper

object CarUiBridge {
    private val main = Handler(Looper.getMainLooper())
    private var appPickerListener: (() -> Unit)? = null

    @Synchronized fun setAppPickerListener(listener: () -> Unit) { appPickerListener = listener }
    @Synchronized fun clearAppPickerListener(listener: () -> Unit) {
        if (appPickerListener === listener) appPickerListener = null
    }
    fun openAppPicker() {
        main.post { synchronized(this) { appPickerListener }?.invoke() }
    }
}
