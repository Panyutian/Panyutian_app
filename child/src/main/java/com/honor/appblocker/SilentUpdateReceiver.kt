package com.honor.appblocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * PackageInstaller 静默安装结果接收器（v22）
 *
 * DeviceOwner 特权：通过 PackageInstaller Session.commit 安装应用时
 * 无需用户弹窗确认，安装结果通过本接收器回调。
 * - SUCCESS：新版已装上，重新应用管控策略 + 发成功通知
 * - PENDING_USER_ACTION：极端情况下系统仍要求确认，拉起确认页
 * - 其他失败：发失败通知，便于排查
 */
class SilentUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SilentUpdate"
        const val CHANNEL_ID = "honor_update_channel"
        private const val NOTIF_ID = 9529
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.w(TAG, "📦 安装结果回调: status=$status msg=$msg")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 需要用户确认（理论上 DeviceOwner 不会走到这里）：把系统确认页拉起来
                val confirmIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(it) }
                        .onFailure { e -> Log.e(TAG, "拉起安装确认页失败: ${e.message}") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // 安装成功：新版进程已就绪，立即重新应用全部管控策略
                runCatching { AdminReceiver.enforceAllPolicies(context) }
                showNotif(context, "✅ 系统更新完成", "当前已是最新版本，守护运行正常")
                Log.w(TAG, "✅ 静默安装成功，策略已重新应用")
            }
            else -> {
                showNotif(context, "⚠️ 系统更新失败", "状态码 $status${if (msg != null) "：$msg" else ""}")
                Log.e(TAG, "❌ 静默安装失败 status=$status msg=$msg")
            }
        }
    }

    private fun showNotif(context: Context, title: String, text: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(CHANNEL_ID, "系统更新", NotificationManager.IMPORTANCE_HIGH)
                    nm.createNotificationChannel(ch)
                }
            }
            val pi = PendingIntent.getActivity(
                context, 0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }
}
