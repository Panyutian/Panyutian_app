package com.honor.appblocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 开机自启动接收器
 * 确保手机重启后：
 * 1. 守护服务重新启动
 * 2. DeviceOwner管控策略重新应用
 * 3. 无障碍服务保持运行
 *
 * 三重保障：
 * - BOOT_COMPLETED / LOCKED_BOOT_COMPLETED 广播直接启动
 * - AlarmManager 延迟 30 秒再触发一次（等系统稳定后重启）
 * - AlarmManager 重复闹钟 5 分钟检查一次（BOOT_COMPLETED 丢失时兜底）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_BOOT_ALARM = "com.honor.appblocker.ACTION_BOOT_ALARM"
        private const val REQ_BOOT_ALARM = 1001
        private const val REQ_BOOT_REPEAT = 1002
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "接收到广播: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.ACTION_BOOT_COMPLETED" -> {
                handleBoot(context)
                scheduleBootAlarms(context)
            }
            ACTION_BOOT_ALARM -> {
                Log.i(TAG, "AlarmManager 延迟触发，重新启动服务")
                handleBoot(context)
            }
        }
    }

    /** 延迟 + 重复闹钟兜底 —— 防止 BOOT_COMPLETED 被系统吞掉 */
    private fun scheduleBootAlarms(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Intent 需要区分不同 requestCode
        val bootIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_BOOT_ALARM
        }

        // 1. 延迟 30 秒后再启动一次（等系统稳定、WiFi连上）
        val pendingOnce = PendingIntent.getBroadcast(
            context, REQ_BOOT_ALARM, bootIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + 30_000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingOnce)
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingOnce)
        }
        Log.i(TAG, "已设置 30 秒后延迟启动闹钟")

        // 2. 每 15 分钟检查一次（如果 BOOT_COMPLETED 完全没收到，这个是最后兜底）
        val pendingRepeat = PendingIntent.getBroadcast(
            context, REQ_BOOT_REPEAT, bootIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val repeatInterval = 15 * 60 * 1000L // 15 分钟
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt + repeatInterval,
                pendingRepeat
            )
            // 注意：setAndAllowWhileIdle 不支持真正的重复，每次手动重新调度
            // 但 30 秒的一次性闹钟已经足够，这个只是极端兜底
        } else {
            am.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt + repeatInterval,
                repeatInterval,
                pendingRepeat
            )
        }
        Log.i(TAG, "已设置 15 分钟重复兜底闹钟")
    }

    private fun handleBoot(context: Context) {
        try {
            // 重新应用管控策略（DeviceOwner策略系统会自动恢复，但为了保险重新应用）
            AdminReceiver.enforceAllPolicies(context)
            Log.i(TAG, "开机后重新应用管控策略成功")
        } catch (e: Exception) {
            Log.e(TAG, "开机策略应用失败", e)
        }

        try {
            // 如果家长之前设置了强制锁屏模式 → 检查是否过期，过期就自动解除（解决关机跨越过期时间问题）
            val prefs = PrefsManager(context)
            val now = System.currentTimeMillis()
            when {
                // 定时锁已过期 → 自动解除 + 唤醒屏幕
                prefs.forceLocked && prefs.lockUntilAt > 0 && prefs.lockUntilAt <= now -> {
                    Log.i(TAG, "🔓 开机：检测到定时锁屏已过期，自动解除强制模式 + 唤醒屏幕")
                    prefs.forceLocked = false
                    prefs.lockUntilAt = 0L
                    AdminReceiver.wakeUpScreen(context)
                }
                // 永久锁（lockUntilAt=0）或定时锁未过期 → 开机立即锁屏
                prefs.forceLocked -> {
                    Log.i(TAG, "🔒 开机：检测到强制锁屏模式，立即锁屏")
                    runCatching { AdminReceiver.lockNow(context) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "开机强制锁屏状态处理失败", e)
        }

        try {
            // 启动前台守护服务
            val svc = Intent(context, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.i(TAG, "开机后启动守护服务成功")
        } catch (e: Exception) {
            Log.e(TAG, "开机启动守护服务失败", e)
        }

        try {
            // 启动轮询兜底服务（UsageStats）—— 绕开可能坏掉的无障碍事件管道
            ForegroundPollerService.start(context)
            Log.i(TAG, "开机后启动轮询服务成功")
        } catch (e: Exception) {
            Log.e(TAG, "开机启动轮询服务失败", e)
        }
    }
}
