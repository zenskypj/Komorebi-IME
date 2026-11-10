package me.zssu.ime.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.zssu.ime.settings.AboutActivity
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

internal object UpdateManager {
    private const val PREFS = "release_update_check"
    private const val JOB_ID = 0x4b5550
    private const val CHANNEL = "release_updates"
    private const val API = "https://api.github.com/repos/zenskypj/Komorebi-IME/releases?per_page=100"
    private val checking = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean("enabled", value).apply()
        if (value) schedule(context) else context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
    fun schedule(context: Context): Boolean = runCatching {
        if (!enabled(context)) return@runCatching false
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return@runCatching true
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(24 * 60 * 60 * 1000L)
            .setPersisted(true)
            .build()
        scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)
    fun scheduled(context: Context): Boolean = runCatching {
        context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null
    }.getOrDefault(false)
    fun installedVersion(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
    fun latest(context: Context): String? = preferences(context).getString("latest_tag", null)
    fun stable(context: Context): String? = preferences(context).getString("stable_tag", null)
    fun isNewer(context: Context, tag: String?): Boolean {
        val installed = ReleaseVersion.parse(installedVersion(context)) ?: return false
        val version = tag?.let(ReleaseVersion::parse) ?: return false
        return version > installed
    }
    fun updateAvailable(context: Context): Boolean {
        return isNewer(context, latest(context))
    }
    fun releaseUrl(tag: String): String = "https://github.com/zenskypj/Komorebi-IME/releases/tag/" + URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
    fun checkAsync(context: Context, notify: Boolean, callback: (String?) -> Unit) {
        if (!checking.compareAndSet(false, true)) { main.post { callback("更新を確認中です") }; return }
        val app = context.applicationContext
        Thread({
            val error = try {
                val releases = fetchLatest()
                preferences(app).edit().putString("latest_tag", releases.latest).putString("stable_tag", releases.stable)
                    .putLong("checked_at", System.currentTimeMillis()).remove("error").apply()
                if (notify && enabled(app)) {
                    val tag = releases.stable?.takeIf { isNewer(app, it) && preferences(app).getString("notified_stable", null) != it }
                        ?: releases.latest
                    showNotification(app, tag)
                }
                null
            } catch (e: Exception) {
                "更新情報を取得できませんでした。通信状況を確認してください。".also {
                    preferences(app).edit().putString("error", it).apply()
                }
            } finally { checking.set(false) }
            main.post { callback(error) }
        }, "release-update-check").apply { isDaemon = true; start() }
    }
    private fun fetchLatest(): ReleaseSelector.Releases {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Komorebi-IME-Update-Check")
            check(conn.responseCode == 200)
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    check(out.size() + n <= 4 * 1024 * 1024)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return ReleaseSelector.select(String(bytes, Charsets.UTF_8))
        } finally { conn.disconnect() }
    }
    private fun showNotification(context: Context, tag: String) {
        val stable = ReleaseVersion.parse(tag)?.isStable == true
        val notifiedKey = if (stable) "notified_stable" else "notified_preview"
        if (!isNewer(context, tag) || preferences(context).getString(notifiedKey, null) == tag) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "更新のお知らせ", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(context, AboutActivity::class.java)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (stable) "Komorebi IME の正式版があります" else "Komorebi IME の更新があります")
            .setContentText("$tag を確認できます")
            .setAutoCancel(true).setContentIntent(pending).build()
        try {
            manager.notify(JOB_ID, notification)
            preferences(context).edit().putString(notifiedKey, tag).apply()
        } catch (_: SecurityException) { /* The user can still check in About. */ }
    }
}
