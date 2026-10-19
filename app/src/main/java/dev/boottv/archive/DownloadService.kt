package dev.boottv.archive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Free Movies downloads going while BootTV is in the background (another app open, TV idle).
 * Runs only while something is downloading: holds a partial wake lock and a Wi-Fi lock, shows progress, then stops.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Movie downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), type) else startForeground(ID, notification())

        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BootTV:downloads").apply { acquire(6 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BootTV:downloads")?.apply { acquire() }

        scope.launch {
            while (true) {
                delay(2000)
                if (ArchiveDownloads.states.values.none { it is DownloadState.Running }) { stopSelf(); break }
                nm.notify(ID, notification())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val running = ArchiveDownloads.states.entries.filter { it.value is DownloadState.Running }
        val title = when (running.size) {
            0 -> "Finishing downloads"
            1 -> "Downloading ${ArchiveDownloads.items[running[0].key]?.title ?: "a movie"}"
            else -> "Downloading ${running.size} movies"
        }
        val progress = running.map { (it.value as DownloadState.Running).progress }.average().takeIf { !it.isNaN() } ?: 0.0
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Free Movies · ${(progress * 100).toInt()}%")
            .setProgress(100, (progress * 100).toInt(), running.isEmpty())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 4201

        /** Called when a download starts; harmless if the service is already running. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            // Android can refuse a foreground start while BootTV is in the background; the download still runs then.
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w("DownloadService", "couldn't start", it) }
        }
    }
}
