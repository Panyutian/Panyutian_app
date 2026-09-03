package com.honor.appblocker

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Application级入口
 */
class App : Application() {

    companion object {
        private const val TAG = "HonorAppBlockerApp"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 启动时尽量确保管控策略生效（用户可能重新打开本应用）
        try {
            AdminReceiver.enforceAllPolicies(this)
        } catch (e: Exception) {
            Log.w(TAG, "启动时策略重入失败: ${e.message}")
        }

        // 启动前台守护服务，防止被系统回收
        try {
            val intent = Intent(this, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "守护服务启动失败: ${e.message}")
        }
    }
}
