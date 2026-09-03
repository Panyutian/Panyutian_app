package com.honor.appblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台守护服务
 *
 * 目的：
 * 1. 提升进程优先级，避免被荣耀MagicUI后台清理
 * 2. 周期性检查管控策略和无障碍服务状态，必要时自恢复
 *
 * 荣耀系统后台杀进程非常激进，前台服务是目前最可靠的保活手段。
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "honor_app_blocker_guard"
        private const val NOTIF_ID = 10086
        private const val CHECK_INTERVAL_MS = 60_000L // 1分钟检查一次
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val periodicCheck = object : Runnable {
        override fun run() {
            try {
                performHealthCheck()
            } catch (e: Exception) {
                Log.e(TAG, "健康检查异常", e)
            } finally {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    // ================= 生命周期 =================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "守护服务启动")

        // 首次健康检查 + 周期调度
        handler.postDelayed(periodicCheck, 5_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicCheck)
        Log.i(TAG, "守护服务销毁")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= 健康检查逻辑 =================

    private fun performHealthCheck() {
        val ctx = applicationContext

        // 1. DeviceOwner权限：确保限制仍然生效（系统偶发清除用户限制）
        if (AdminReceiver.isDeviceOwner(ctx) || AdminReceiver.isAdminActive(ctx)) {
            AdminReceiver.enforceAllPolicies(ctx)
        }

        // 2. 无障碍服务：若未运行，则记录日志（用户需手动开启，但可尝试通过系统设置唤起）
        if (!GameBlockAccessibilityService.isRunning()) {
            Log.w(TAG, "无障碍服务未运行，请在系统设置中重新开启")
        }

        // 3. 轮询服务（含 HTTP server）：如果挂了立即重启 —— 家长端远程控制依赖它
        if (!ForegroundPollerService.isRunning()) {
            Log.w(TAG, "轮询服务/HTTP server 未运行，重启中...")
            ForegroundPollerService.start(ctx)
        }

        // 4. 日志记录状态
        val doOwner = AdminReceiver.isDeviceOwner(ctx)
        val admin = AdminReceiver.isAdminActive(ctx)
        val a11y = GameBlockAccessibilityService.isRunning()
        val poller = ForegroundPollerService.isRunning()
        Log.d(TAG, "健康检查 - DeviceOwner=$doOwner, Admin=$admin, A11y=$a11y, Poller=$poller")
    }

    // ================= 前台通知 =================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "应用管控守护服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于保持应用管控功能持续运行，防止被系统清理"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val ctx = applicationContext
        val doOwner = AdminReceiver.isDeviceOwner(ctx)
        val a11y = GameBlockAccessibilityService.isRunning()

        val statusText = buildString {
            append("管控运行中  ")
            append(if (doOwner) "DeviceOwner:✅" else "DeviceOwner:❌")
            append("  ")
            append(if (a11y) "无障碍:✅" else "无障碍:❌")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("荣耀应用更新 守护中")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
}
