package com.sedationh.voicesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class VoiceSyncService : Service() {
    
    companion object {
        private const val CHANNEL_ID = "voice_sync_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.sedationh.voicesync.STOP_SERVICE"
        
        fun start(context: Context, ipAddress: String = "") {
            val intent = Intent(context, VoiceSyncService::class.java).apply {
                putExtra("IP_ADDRESS", ipAddress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, VoiceSyncService::class.java)
            context.stopService(intent)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        val ipAddress = intent?.getStringExtra("IP_ADDRESS") ?: ""
        val notification = createNotification(ipAddress)
        startForeground(NOTIFICATION_ID, notification)
        
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VoiceSync 快速启动",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "点击通知快速打开 VoiceSync"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(ipAddress: String): Notification {
        // 点击通知打开应用
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // 关闭服务的动作
        val stopIntent = Intent(this, VoiceSyncService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val contentText = if (ipAddress.isNotEmpty()) {
            "Mac: $ipAddress"
        } else {
            "点击打开应用"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceSync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload) // 使用系统上传图标
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "关闭",
                stopPendingIntent
            )
            .setOngoing(true) // 不可滑动删除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setColor(0xFF66BB6A.toInt()) // 浅绿色
            .build()
    }
    
    fun updateNotification(ipAddress: String) {
        val notification = createNotification(ipAddress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
