package com.example.aamirror

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class UpdateInfo(val versionName: String, val downloadUrl: String, val releaseUrl: String)

object UpdateChecker {
    private const val API = "https://api.github.com/repos/ledinhtien219/AA-Mirror/releases/latest"
    private val checking = AtomicBoolean(false)

    private fun currentVersion(c: Context) = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (_: Throwable) { "0" }

    private fun parts(v: String) = v.removePrefix("v").split('.').map {
        it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0
    }

    fun isNewer(remote: String, local: String): Boolean {
        val a=parts(remote); val b=parts(local); val n=maxOf(a.size,b.size)
        for(i in 0 until n) {
            val av=a.getOrElse(i){0}; val bv=b.getOrElse(i){0}
            if(av!=bv) return av>bv
        }
        return false
    }

    fun fetchLatest(): UpdateInfo? {
        val c=(URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout=7000; readTimeout=7000
            setRequestProperty("Accept","application/vnd.github+json")
            setRequestProperty("User-Agent","AA-Mirror-Updater")
        }
        return try {
            if(c.responseCode !in 200..299) return null
            val root=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
            val tag=root.optString("tag_name").removePrefix("v")
            if(tag.isBlank()) return null
            val release=root.optString("html_url")
            var apk=""
            root.optJSONArray("assets")?.let { arr ->
                for(i in 0 until arr.length()) {
                    val o=arr.optJSONObject(i) ?: continue
                    if(o.optString("name").endsWith(".apk",true)) {
                        apk=o.optString("browser_download_url"); break
                    }
                }
            }
            UpdateInfo(tag, apk.ifBlank{release}, release)
        } catch (_: Throwable) { null } finally { c.disconnect() }
    }

    fun checkInApp(a: Activity, force: Boolean=false) {
        if(!checking.compareAndSet(false,true)) return
        Thread {
            try {
                val latest=fetchLatest(); val local=currentVersion(a)
                val newer=latest!=null && isNewer(latest.versionName,local)
                a.runOnUiThread {
                    if(a.isFinishing || a.isDestroyed) return@runOnUiThread
                    if(!newer || latest==null) {
                        if(force) AlertDialog.Builder(a).setTitle("Cập nhật AA Mirror")
                            .setMessage("Bạn đang dùng bản mới nhất: v${local}")
                            .setPositiveButton("OK",null).show()
                        return@runOnUiThread
                    }
                    val p=a.getSharedPreferences("updates",Context.MODE_PRIVATE)
                    if(!force && p.getString("last_prompted","")==latest.versionName) return@runOnUiThread
                    p.edit().putString("last_prompted",latest.versionName).apply()
                    AlertDialog.Builder(a).setTitle("Có AA Mirror v${latest.versionName}")
                        .setMessage("Đã có bản mới. Bấm Tải bản mới để cài cập nhật.")
                        .setNegativeButton("Để sau",null)
                        .setPositiveButton("Tải bản mới"){_,_->openUrl(a,latest.downloadUrl)}.show()
                }
            } finally { checking.set(false) }
        }.start()
    }

    fun openUrl(c: Context,url:String) {
        try { c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) {}
    }
}

object UpdateNotifications {
    const val CHANNEL_ID="aamirror_updates"
    fun init(c: Context) {
        if(Build.VERSION.SDK_INT>=26) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID,"Cập nhật AA Mirror",NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val req=PeriodicWorkRequestBuilder<UpdateWorker>(24,TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "aamirror_update_check",ExistingPeriodicWorkPolicy.UPDATE,req)
    }
    fun show(c: Context,i:UpdateInfo) {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val pi=PendingIntent.getActivity(c,3001,Intent(Intent.ACTION_VIEW,Uri.parse(i.downloadUrl)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=NotificationCompat.Builder(c,CHANNEL_ID).setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle("AA Mirror v${i.versionName} đã có").setContentText("Chạm để tải và cập nhật")
            .setAutoCancel(true).setContentIntent(pi).build()
        try { NotificationManagerCompat.from(c).notify(3001,n) } catch (_: SecurityException) {}
    }
}

class UpdateWorker(c:Context,p:WorkerParameters):Worker(c,p) {
    override fun doWork():Result {
        val latest=UpdateChecker.fetchLatest() ?: return Result.retry()
        val local=try { applicationContext.packageManager.getPackageInfo(applicationContext.packageName,0).versionName?:"0" }
            catch (_:Throwable){"0"}
        if(UpdateChecker.isNewer(latest.versionName,local)) UpdateNotifications.show(applicationContext,latest)
        return Result.success()
    }
}
