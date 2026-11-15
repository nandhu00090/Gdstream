package com.gdstream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Separate from downloads: a completed download must never stop an external player's proxy. */
class ArchiveStreamService : Service() {
    companion object {
        private const val CHANNEL = "gdstream_archive_playback"
        private const val NOTIFICATION = 8802
        private val lifetime = ArchiveBridgeLifetime()
        fun start(context: Context) {
            lifetime.launched()
            DebugLogger.log("ARCHIVE_BRIDGE_START", "Starting mediaPlayback foreground service")
            val intent = Intent(context, ArchiveStreamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
        fun setAppForeground(value: Boolean) {
            lifetime.foreground(value)
            DebugLogger.log("ARCHIVE_BRIDGE_ACTIVITY", "appForeground=$value; active streams retain bridge")
        }
        fun clientOpened() = lifetime.opened()
        fun clientClosed() = lifetime.closed()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val checkIdle = object : Runnable {
        override fun run() {
            if (lifetime.canStop()) stopSelf() else handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLogger.initialize(applicationContext)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "External video playback", NotificationManager.IMPORTANCE_LOW))
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
            val stop = PendingIntent.getService(this, 0, Intent(this, ArchiveStreamService::class.java).setAction("STOP"), flags)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("GDStream Archive Bridge")
                .setContentText("Streaming to external player").setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open).addAction(0, "Stop", stop).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else startForeground(NOTIFICATION, notification)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GDStream::ArchivePlayback").apply {
                    setReferenceCounted(false)
                    @Suppress("WakelockTimeout")
                    acquire() // Released by service destruction / explicit Stop, including long BD50 playback.
                }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GDStream::ArchiveWifi").apply { acquire() }
            handler.postDelayed(checkIdle, 5000)
            DebugLogger.log("ARCHIVE_BRIDGE_READY", "Foreground service and playback locks active")
        } catch (error: Exception) {
            DebugLogger.log("ARCHIVE_BRIDGE_ERR", isoFailureDetails(error))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        // A restarted process cannot resurrect its old loopback registrations; do not advertise one.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkIdle)
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } finally {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        DebugLogger.log("ARCHIVE_BRIDGE_STOP", "Playback service released; listener remains available while app lives")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
