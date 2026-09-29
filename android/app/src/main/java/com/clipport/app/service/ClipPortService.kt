package com.clipport.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.clipport.app.MainActivity
import com.clipport.app.Prefs
import com.clipport.app.SyncManager
import com.clipport.app.clip.ClipFilter
import com.clipport.app.xposed.BackgroundClipboardCache

/**
 * 前台服务（D-10：foregroundServiceType="connectedDevice"，静默通知渠道对齐小米 hide_foreground）。
 * 持有 ClipboardWatcher（监听+降级）与 SyncManager。
 */
class ClipPortService : Service() {
    companion object {
        const val CHANNEL_ID = "hide_foreground"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.clipport.app.START"
        @Volatile var running = false
        @Volatile var statusText = "未启动"
        @Volatile var instance: ClipPortService? = null
    }

    fun requestPair() {
        val m = manager ?: return
        m.pairingMode = true
        m.connect(pinned = false)
    }

    fun requestConnect() {
        manager?.connect(pinned = Prefs(this).serverFpHex != null)
    }

    private var manager: SyncManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var cm: ClipboardManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        startAsForeground()
        val prefs = Prefs(this)
        manager = SyncManager(this, prefs) { s ->
            statusText = s
            android.util.Log.i("ClipPortService", s)
        }
        manager?.connect(pinned = prefs.serverFpHex != null)

        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val l = ClipboardManager.OnPrimaryClipChangedListener {
            // 降级通道：先消费悬浮窗读取到的内容缓存（若在 2s 内有更新）
            val fb = BackgroundClipboardCache.lastText
            if (fb != null && System.currentTimeMillis() - BackgroundClipboardCache.lastAtMs < 2000) {
                manager?.onLocalClipChanged()
            } else {
                manager?.onLocalClipChanged()
            }
        }
        listener = l
        cm?.addPrimaryClipChangedListener(l)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        listener?.let { cm?.removePrimaryClipChangedListener(it) }
        manager?.disconnect()
        running = false
        instance = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ClipPort", NotificationManager.IMPORTANCE_NONE)
        )
        val tap = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipPort 运行中")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }
}

/** 开机自启（RECEIVE_BOOT_COMPLETED 属允许后台启动 FGS 的豁免场景，spec §4.2）。 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, ClipPortService::class.java).setAction(ClipPortService.ACTION_START))
        }
    }
}
