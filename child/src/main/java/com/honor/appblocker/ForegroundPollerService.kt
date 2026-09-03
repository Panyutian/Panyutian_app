package com.honor.appblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.accessibilityservice.AccessibilityService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlin.concurrent.thread
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * 前台轮询服务 —— 绕过坏掉的无障碍事件管道
 *
 * 每 3 秒用 UsageStatsManager 主动查询"当前前台是什么 App + 什么 Activity"，
 * 然后用 GameBlockMatcher.shouldBlock 判断是否需要拦截。
 */
class ForegroundPollerService : Service() {

    companion object {
        private const val TAG = "HonorPoller"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "honor_appblocker_poller"
        private const val HTTP_PORT = 8080

        @Volatile private var _running: Boolean = false

        /** 检测轮询服务是否在运行（不再依赖 HTTP 端口） */
        fun isRunning(): Boolean = _running

        /** 启动轮询服务 */
        fun start(ctx: Context) {
            Log.d(TAG, "start() called from ${javaClass.simpleName}")
            val intent = Intent(ctx, ForegroundPollerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed: ${e.message}", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsManager
    private var httpServer: HttpCmdServer? = null
    private var mqttBridge: MqttBridge? = null

    // ===== 屏幕开关广播（息屏降频、亮屏立即触发一次）=====
    private val screenReceiver: android.content.BroadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_ON -> {
                    Log.w(TAG, "🔆 屏幕亮起 → 立即触发一次轮询")
                    handler.removeCallbacks(pollRunnable)
                    handler.post(pollRunnable)  // 立即执行一次
                }
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    Log.w(TAG, "🌙 屏幕关闭 → 降频到 30 秒")
                    AppHistoryStore.onLeaveApp(this@ForegroundPollerService)  // 息屏结束当前 APP 记录
                }
            }
        }
    }

    private val BLOCK_THROTTLE_MS = 4000L
    private val lastBlockTime = mutableMapOf<String, Long>()
    private var lastFrontPkg: String? = null
    private var lastFrontTitle: String = ""
    private var lastBlockedPkg: String? = null
    private var lastBlockedTitle: String = ""
    private var lastBlockTimeMs: Long = 0L
    private var pauseJustExpired: Boolean = false  // 暂停到期后强制立即拦截一次
    private var lastForceLockMs: Long = 0L  // 上次强制锁屏时间

    // ===== 动态轮询间隔（低功耗待机 · 三级）=====
    private val INTERVAL_ACTIVE = 3_000L       // 亮屏 + 前台是"目标拦截类"：3 秒（必须快）
    private val INTERVAL_IDLE = 15_000L         // 亮屏 + 前台非目标：15 秒（足够快拦截用户切换）
    private val INTERVAL_OFF = 30_000L          // 息屏：30 秒（只保持 MQTT）
    private val INTERVAL_PAUSED = 30_000L       // 暂停拦截中：30 秒（根本不需要查游戏）

    /** 需要高频轮询的前台类（可能启动游戏/小程序） */
    private val TARGET_PKG_KEYWORDS = listOf(
        "tencent.mm",        // 微信（小游戏）
        "tencent.mobileqq",  // QQ（小游戏）
        "tencent.tim",       // TIM
        "weixin",            // 微信通用
        "qqgame",            // QQ 游戏中心
        "qqbrowser",         // QQ 浏览器（小游戏）
        "ucbrowser",         // UC 浏览器（小游戏）
        "qqmusic",           // QQ 音乐
        "netease.cloudmusic",// 网易云音乐
        "alipay",            // 支付宝
        "bilibili",          // B 站（游戏中心 biligame）
        "taptap",            // TapTap
        "game",              // 任何含 game 的包名（王者荣耀/和平精英等）
        "mini",              // mini 类（小程序容器）
    )

    /** 判断屏幕是否亮着 */
    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return runCatching { pm.isInteractive }.getOrDefault(true)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                pollOnce()
            } catch (e: Exception) {
                Log.e(TAG, "pollOnce exception: ${e.message}", e)
            }
            handler.postDelayed(this, currentPollInterval())
        }
    }

    /** 根据当前状态 + 前台 App 类型动态决定轮询间隔 */
    private fun currentPollInterval(): Long {
        // 暂停拦截期间 → 30 秒（不需要检测游戏了）
        if (prefs.isPaused()) return INTERVAL_PAUSED
        // 息屏 → 30 秒
        if (!isScreenOn()) return INTERVAL_OFF
        // 亮屏：根据前台 App 类型决定
        val pkg = lastFrontPkg
        if (pkg != null && TARGET_PKG_KEYWORDS.any { pkg.contains(it, true) }) {
            return INTERVAL_ACTIVE   // 目标类 → 3 秒（快速拦截）
        }
        return INTERVAL_IDLE          // 非目标类（Launcher/电子书/相机/浏览器/设置...）→ 15 秒
    }

    override fun onCreate() {
        super.onCreate()
        _running = true
        Log.i(TAG, "=== ForegroundPollerService onCreate ===")
        prefs = PrefsManager(this)
        Log.d(TAG, "prefs: blockWechatGame=${prefs.blockWechatGame}, blockBrowserGame=${prefs.blockBrowserGame}, blockShortVideoGame=${prefs.blockShortVideoGame}, blockPopupGame=${prefs.blockPopupGame}")
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        // 注册屏幕开关广播（息屏降频、亮屏立即触发）
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        handler.postDelayed(pollRunnable, 1500L)
        Log.i(TAG, "轮询已启动（三级动态间隔）")
        // 创建 HTTP Server 对象作为"命令处理逻辑容器"（不启动端口监听，省电）
        httpServer = HttpCmdServer(this, HTTP_PORT)
        // v3.0：启动 MQTT 跨网络远程控制桥接
        mqttBridge = MqttBridge(this).apply {
            cmdHandler = { path, params ->
                httpServer?.handleRequestPublic(path, params)
                    ?: "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n\r\n{\"error\":\"服务未就绪\"}"
            }
            start()
        }
        Log.i(TAG, "v3.0 MQTT 桥接已启动（跨网络远程控制，HTTP端口已关闭省电）")
    }

    /**
     * 家长远程触发的静默更新：
     * 1. 后台下载最新 APK
     * 2. 下载完 → 临时关闭禁装策略 + 全屏通知触发系统包安装器
     * 3. 新版本启动时 enforceAllPolicies() 自动恢复禁装
     */
    fun triggerSilentUpdate() {
        thread(start = true, name = "SilentUpdate") {
            android.util.Log.i(TAG, "🔄 开始静默更新流程...")
            var tempFile: File? = null
            try {
                // Step 1: 拉 version.json 拿 child 最新 APK 地址
                // 三条线路依次尝试：jsDelivr CDN → ghfast.top 国内镜像 → GitHub raw 直连
                val jsonUrls = listOf(
                    "https://cdn.jsdelivr.net/gh/Panyutian/Panyutian_app@main/version.json",
                    "https://ghfast.top/https://raw.githubusercontent.com/Panyutian/Panyutian_app/main/version.json",
                    "https://raw.githubusercontent.com/Panyutian/Panyutian_app/main/version.json"
                )
                var body: String? = null
                for (u in jsonUrls) {
                    try {
                        val conn = URL(u).openConnection() as HttpURLConnection
                        conn.connectTimeout = 6000; conn.readTimeout = 6000
                        body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        if (!body.isNullOrEmpty()) break
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "版本检查线路失败: $u (${e.message})")
                    }
                }
                if (body.isNullOrEmpty()) {
                    android.util.Log.e(TAG, "❌ 版本检查所有线路都失败")
                    showUpdateDoneNotification("更新失败: 无法连接版本服务器")
                    return@thread
                }

                val json = JSONObject(body!!)
                val child = json.getJSONObject("child")
                val latestCode = child.getInt("versionCode")
                val latestName = child.getString("versionName")
                var apkUrl = child.getString("apkUrl")

                val currentCode = runCatching {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                }.getOrDefault(0)

                android.util.Log.i(TAG, "🔄 版本检查: local=$currentCode remote=$latestCode")
                if (latestCode <= currentCode) {
                    android.util.Log.i(TAG, "🔄 已是最新版本，跳过更新")
                    showUpdateDoneNotification("${packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))} 已是最新版本")
                    return@thread
                }

                // Step 2: 国内镜像替换
                if (apkUrl.contains("github.com")) apkUrl = "https://ghfast.top/$apkUrl"
                android.util.Log.i(TAG, "🔄 下载 APK: $apkUrl")

                // Step 3: 下载 APK（存到应用私有目录，无需存储权限，PackageInstaller 直接用文件描述符）
                val dlConn = URL(apkUrl).openConnection() as HttpURLConnection
                dlConn.connectTimeout = 15000; dlConn.readTimeout = 90000
                dlConn.instanceFollowRedirects = true
                dlConn.connect()

                val updatesDir = File(getExternalFilesDir(null) ?: filesDir, "updates").apply { mkdirs() }
                tempFile = File(updatesDir, "child-update.apk")
                if (tempFile.exists()) tempFile.delete()
                tempFile.outputStream().use { dlConn.inputStream.copyTo(it) }
                dlConn.disconnect()
                android.util.Log.i(TAG, "🔄 下载完成: ${tempFile!!.absolutePath} 大小=${tempFile.length()}")

                // Step 4: 临时关闭禁装策略（兜底，DeviceOwner 静默安装通常不受限）
                AdminReceiver.setBlockInstallPolicy(this, false)
                Thread.sleep(800)  // 等 DPM 生效

                // Step 5: 优先 DeviceOwner 静默安装（PackageInstaller 会话，无需弹窗）
                // 失败再退回：直接拉起安装器 → 全屏通知
                var ok = silentInstallViaPackageInstaller(tempFile!!)
                if (!ok) ok = launchInstallerDirectly(tempFile!!)
                if (!ok) installApkViaNotification(tempFile!!)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ 静默更新失败", e)
                showUpdateDoneNotification("更新失败: ${e.message}")
            }
        }
    }

    /**
     * v22: DeviceOwner 通过 PackageInstaller 会话 API 静默安装（无需任何弹窗确认）。
     * 设备/资料所有者调用 session.commit() 时，系统跳过用户确认直接安装，
     * 结果通过 [SilentUpdateReceiver] 回调。
     * @return true 表示已成功提交安装会话（最终成功与否看接收器回调）
     */
    private fun silentInstallViaPackageInstaller(apkFile: File): Boolean {
        return runCatching {
            if (!AdminReceiver.isDeviceOwner(this)) {
                android.util.Log.w(TAG, "非 DeviceOwner，跳过静默安装")
                return false
            }
            val installer = packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(packageName)
            }
            val sessionId = installer.createSession(params)
            android.util.Log.i(TAG, "📦 PackageInstaller 会话已创建 id=$sessionId")
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("child_update", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(this, SilentUpdateReceiver::class.java).apply {
                    action = "com.honor.appblocker.INSTALL_STATUS"
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(this, 0, statusIntent, flags)
                // commit 后系统静默安装，设备所有者无需用户确认；结果回调到 SilentUpdateReceiver
                session.commit(pending.intentSender)
            }
            android.util.Log.i(TAG, "📦 PackageInstaller 已提交静默安装（设备所有者免确认）")
            true
        }.getOrElse {
            android.util.Log.e(TAG, "❌ PackageInstaller 静默安装失败: ${it.message}", it)
            false
        }
    }

    private fun launchInstallerDirectly(apkFile: File): Boolean {
        return runCatching {
            val authority = "${packageName}.fileprovider"
            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, authority, apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            // 先确保"允许安装未知来源"已开启
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Thread.sleep(1500)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            startActivity(installIntent)
            android.util.Log.i(TAG, "✅ 直接拉起安装器成功")
            true
        }.getOrElse {
            android.util.Log.e(TAG, "❌ 直接拉起安装器失败，尝试通知方式", it)
            false
        }
    }

    private fun installApkViaNotification(apkFile: File) {
        val authority = "${packageName}.fileprovider"
        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, authority, apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(apkUri, "application/vnd.android.package-archive")
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 9527, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "honor_update_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                val ch = NotificationChannel(channelId, "系统更新", NotificationManager.IMPORTANCE_HIGH)
                ch.enableLights(true); ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("🔄 系统更新已就绪")
            .setContentText("点击安装")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)  // Android 10+ 前台服务全屏通知
            .build()

        nm.notify(9527, notif)
        android.util.Log.i(TAG, "🔔 更新安装通知已发送，全屏 PendingIntent 已设置")
    }

    private fun showUpdateDoneNotification(text: String) {
        val channelId = "honor_update_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                val ch = NotificationChannel(channelId, "系统更新", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
            }
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("系统更新")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        nm.notify(9528, notif)
    }

    override fun onDestroy() {
        _running = false
        Log.w(TAG, "=== ForegroundPollerService onDestroy ===")
        handler.removeCallbacks(pollRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        mqttBridge?.stop()
        mqttBridge = null
        httpServer?.stop()
        httpServer = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand startId=$startId")
        if (!handler.hasCallbacks(pollRunnable)) {
            handler.postDelayed(pollRunnable, 500L)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollOnce() {
        val now = System.currentTimeMillis()

        // ===== 暂停到期自动清理 =====
        // pauseUntil > 0 但已过期 → 暂停刚结束，需要重置去重状态让拦截立即生效
        if (prefs.pauseUntil > 0 && prefs.pauseUntil <= now) {
            Log.w(TAG, "⏰ 暂停时间到！立即恢复拦截 + 强制踢游戏")
            prefs.pauseUntil = 0L
            // 重置去重状态
            lastBlockedPkg = null
            lastBlockedTitle = ""
            lastBlockTimeMs = 0L
            lastFrontPkg = null
            lastFrontTitle = ""
            // 刷新通知栏
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildNotification())
            pauseJustExpired = true
            // 立即推送新心跳
            mqttBridge?.immediatePublishStatus()
            // 先强制 goHome 一次（不管当前是什么 app），让游戏退到后台
            runCatching { goHome() }
            // 延迟 500ms 后再触发一次完整 pollOnce（因为刚 goHome 完前台可能是桌面）
            handler.postDelayed({
                runCatching {
                    pauseJustExpired = true  // 再次标记
                    pollOnce()
                }
            }, 500)
        }

        // ===== 强制锁屏：到期自动解除 =====
        // 1. forceLocked=true 且 lockUntilAt>0 且 lockUntilAt<=now → 定时锁过期，立即自动解除
        if (prefs.forceLocked && prefs.lockUntilAt > 0 && prefs.lockUntilAt <= now) {
            Log.i(TAG, "⏰ 定时锁屏到期，自动解除强制模式 + 唤醒屏幕")
            prefs.forceLocked = false
            prefs.lockUntilAt = 0L
            AdminReceiver.wakeUpScreen(this)
        }

        // ===== 强制锁屏模式：每 10 秒自动锁一次 =====
        if (prefs.forceLocked && now - lastForceLockMs >= 10_000L) {
            val ok = AdminReceiver.lockNow(this)
            lastForceLockMs = now
            Log.i(TAG, "🔒 强制模式自动锁屏（每10秒）: $ok")
        }

        // ===== 方法 1: 从无障碍服务主动拉 rootInActiveWindow =====
        var lastFgPkg: String? = null
        var lastFgClass: String? = null
        var allWindowTitles = ""
        var windowCount = 0
        var selectedTitle = ""
        // 全局"可疑窗口"检测：遍历所有窗口找 title 不是主界面名的社交 App 窗口
        var suspiciousPkg = ""
        var suspiciousTitle = ""
        val mainUITitles = mapOf(
            "com.tencent.mm" to listOf("微信", "wechat"),
            "com.tencent.mobileqq" to listOf("qq"),
            "com.tencent.tim" to listOf("tim", "腾讯tim"),
            "com.ss.android.ugc.aweme" to listOf("抖音"),
            "com.smile.gifmaker" to listOf("快手"),
            "com.kuaishou.nebula" to listOf("极速版", "快手极速"),
        )

        val a11yInst = GameBlockAccessibilityService.getServiceInstance()
        if (a11yInst != null) {
            val windows = runCatching { a11yInst.windows }.getOrNull()
            if (!windows.isNullOrEmpty()) {
                windowCount = windows.size
                val validWindows = mutableListOf<Pair<String, android.view.accessibility.AccessibilityWindowInfo>>()
                for (win in windows) {
                    val root = runCatching { win.root }.getOrNull()
                    val pkg = root?.packageName?.toString() ?: ""
                    val title = win.title?.toString() ?: ""
                    if (pkg.isNotEmpty() && pkg != packageName && !pkg.contains("launcher", true) && pkg != "com.android.systemui") {
                        validWindows.add(pkg to win)
                    }
                    // 检测可疑窗口：社交 App 包名 + title 不是主界面名
                    val mainUITitlesForPkg = mainUITitles[pkg]
                    if (mainUITitlesForPkg != null && title.isNotEmpty() && !mainUITitlesForPkg.any { it.lowercase() == title.lowercase() }) {
                        suspiciousPkg = pkg
                        suspiciousTitle = title
                    }
                }
                val topEntry = validWindows.maxByOrNull { it.second.type }
                if (topEntry != null) {
                    lastFgPkg = topEntry.first
                    selectedTitle = topEntry.second.title?.toString() ?: ""
                }
            }

            // windows 为空时用 rootInActiveWindow 兜底
            if (lastFgPkg == null || lastFgPkg == packageName) {
                val root = runCatching { a11yInst.rootInActiveWindow }.getOrNull()
                if (root != null) {
                    val pkg = root.packageName?.toString()
                    if (pkg != null && pkg != packageName && !pkg.contains("launcher", true)) {
                        lastFgPkg = pkg
                        selectedTitle = ""
                    }
                }
            }
        }

        // ===== 方法 1.5: 从无障碍服务缓存拿 Activity 类名（荣耀 ROM 限制 ActivityManager.appTasks 的兜底）=====
        val a11yInstForCls = GameBlockAccessibilityService.getServiceInstance()
        if (a11yInstForCls != null && lastFgPkg != null) {
            if (a11yInstForCls.lastWindowPkg == lastFgPkg && !a11yInstForCls.lastWindowCls.isNullOrBlank()) {
                lastFgClass = a11yInstForCls.lastWindowCls
                Log.d(TAG, "【a11y缓存补全】pkg=$lastFgPkg, cls=$lastFgClass")
            }
        }

        // ===== 方法 2: ActivityManager.appTasks 拿真正的 Activity 类名 =====
        if (lastFgClass == null && lastFgPkg != null && lastFgPkg != packageName) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = runCatching { am.appTasks }.getOrNull()
            if (!tasks.isNullOrEmpty()) {
                val top = tasks.firstOrNull()
                val info = top?.taskInfo
                if (info != null) {
                    val topPkg = info.topActivity?.packageName
                    val topActClass = info.topActivity?.className  // 这是真正的 Activity 类名！
                    val topBaseClass = info.baseActivity?.className
                    // 如果 topActivity 包名和 rootInActiveWindow 一致，用它的 className
                    if (topPkg == lastFgPkg && !topActClass.isNullOrEmpty()) {
                        lastFgClass = topActClass
                        Log.d(TAG, "【ActivityManager 补全】pkg=$lastFgPkg, activityClass=$lastFgClass")
                    } else {
                        Log.d(TAG, "【ActivityManager 不一致】topPkg=$topPkg vs fgPkg=$lastFgPkg, topClass=$topActClass, baseClass=$topBaseClass")
                    }
                }
            } else {
                Log.d(TAG, "【ActivityManager 兜底】appTasks 为空（荣耀 ROM 限制）")
            }
        }
        // ===== 方法 2.5: UsageEvents 拿 Activity 类名（MOVE_TO_FOREGROUND 事件有 className）=====
        if (lastFgClass == null && lastFgPkg != null && lastFgPkg != packageName) {
            val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val queryStart = now - 30_000L
            val events = runCatching { usage.queryEvents(queryStart, now + 1_000L) }.getOrNull()
            if (events != null) {
                val e = android.app.usage.UsageEvents.Event()
                var lastClsForPkg: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.packageName == lastFgPkg &&
                        (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                         e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND)) {
                        if (!e.className.isNullOrBlank()) {
                            lastClsForPkg = e.className
                        }
                    }
                }
                if (lastClsForPkg != null) {
                    lastFgClass = lastClsForPkg
                    Log.d(TAG, "【UsageEvents 补全】pkg=$lastFgPkg, cls=$lastFgClass")
                } else {
                    Log.d(TAG, "【UsageEvents 兜底】没找到 $lastFgPkg 的 Activity 类名")
                }
            }
        }

        // ===== 方法 3: UsageStats 最后尝试 =====
        if (lastFgPkg == null || lastFgPkg == packageName) {
            val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val queryStart = now - 60_000L
            val stats = runCatching {
                usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, queryStart, now + 1_000L)
            }.getOrNull()
            if (stats != null && stats.isNotEmpty()) {
                stats.forEach { Log.d(TAG, "  UsageStats: ${it.packageName} lastUsed=${it.lastTimeUsed}") }
                val topApp = stats
                    .filter { it.packageName != packageName && it.packageName != "com.android.systemui" && !it.packageName.contains("launcher", true) }
                    .maxByOrNull { it.lastTimeUsed }
                if (topApp != null && topApp.lastTimeUsed > now - 10_000L) {
                    lastFgPkg = topApp.packageName
                    Log.d(TAG, "【UsageStats 兜底】pkg=$lastFgPkg")
                }
            } else {
                Log.d(TAG, "【UsageStats 兜底】queryUsageStats 返回空")
            }
        }

        Log.d(TAG, "=== 最终结果: fgPkg=$lastFgPkg, fgClass=$lastFgClass ===")

        // 如果发现了可疑窗口（社交 App + 非主界面 title），用它覆盖检测结果
        // 因为小游戏可能是透明窗口叠加在主界面上，selectedTitle 可能还是 "微信"
        if (suspiciousPkg.isNotEmpty()) {
            Log.w(TAG, "⚠️ 可疑窗口优先! 覆盖: pkg=$suspiciousPkg, title='$suspiciousTitle'")
            lastFgPkg = suspiciousPkg
            selectedTitle = suspiciousTitle
        }

        val pkg = lastFgPkg ?: return
        val cls = lastFgClass

        // ===== APP 打开历史记录（家长端"孩子APP动态"数据源）=====
        if (pkg == packageName || pkg.contains("launcher", true) ||
            pkg == "com.android.systemui" || pkg.contains("inputmethod", true)) {
            AppHistoryStore.onLeaveApp(this)  // 回到桌面/系统界面 → 关闭当前记录
        } else {
            AppHistoryStore.onForeground(this, pkg)  // 真实 APP → 记录打开
        }

        if (pkg == packageName) return
        if (pkg.contains("launcher", true)) return
        if (pkg == "com.android.systemui") return
        if (pkg.contains("inputmethod", true)) return

        // QQ 小游戏/游戏中心特殊检测：a11y 冻结时 title 始终 'QQ'，但 totalNodes 会变
        // 实测：QQ 小游戏 totalNodes≈17-18，QQ 主界面≈28，QQ 游戏中心≈40
        // 阈值：<22 判为小游戏，>35 判为游戏中心，22-35 放行（主界面）
        // ⚠️ 阈值依赖 QQ 版本，QQ 更新后可能需要重新校准
        var qqTotalNodes = -1
        if (pkg == "com.tencent.mobileqq") {
            val a11yInst = GameBlockAccessibilityService.getServiceInstance()
            val root = runCatching { a11yInst?.rootInActiveWindow }.getOrNull()
            fun countNodes(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int): Int {
                if (n == null || d > 10) return 0
                var c = 1
                for (i in 0 until runCatching { n.childCount }.getOrDefault(0)) {
                    c += countNodes(runCatching { n.getChild(i) }.getOrNull(), d + 1)
                }
                return c
            }
            qqTotalNodes = countNodes(root, 0)
        }

        // 去重：pkg 和 title 都没变才跳过
        // 社交 App 内切换页面（主界面→小游戏）包名不变但 title 变了，必须重新检查
        // ⚠️ 例外：QQ 小游戏 a11y 会冻结（title 不更新，始终 'QQ'），去重会错过拦截时机
        //     → 对 QQ 不走去重，每次都检查 shouldBlock（QQ 主界面 shouldBlock 返回 null 不会误拦）
        // ⚠️ 例外：暂停刚到期，跳过去重强制立即检查拦截
        val skipDedup = pkg == "com.tencent.mobileqq" || pauseJustExpired
        if (!skipDedup && pkg == lastFrontPkg && selectedTitle == lastFrontTitle && suspiciousPkg.isEmpty()) {
            // 只有当当前窗口和上次"拦截时"完全一样（pkg+title 都匹配）才持续拦截
            // 防止：之前拦了小游戏(title='')，现在进主界面(title='微信')，pkg 没变但 title 变了 → 不应持续拦
            if (pkg == lastBlockedPkg && selectedTitle == lastBlockedTitle &&
                SystemClock.elapsedRealtime() - lastBlockTimeMs > BLOCK_THROTTLE_MS) {
                Log.w(TAG, "持续拦截 $pkg (title='$selectedTitle')")
                goHomeAndToast("系统维护中，该功能暂时不可用")
                lastBlockTimeMs = SystemClock.elapsedRealtime()
            }
            return
        }
        lastFrontPkg = pkg
        lastFrontTitle = selectedTitle

        // QQ 小游戏/游戏中心特殊检测：a11y 冻结时 title 始终 'QQ'，但 totalNodes 会变
        // 实测：QQ 小游戏 totalNodes≈17-18，QQ 主界面≈28，QQ 游戏中心≈40
        // 阈值：<22 判为小游戏，>35 判为游戏中心，22-35 放行（主界面）
        // ⚠️ 阈值依赖 QQ 版本，QQ 更新后可能需要重新校准
        // ⚠️ QQ 启动时主界面加载中 title 会暂时为空，但 totalNodes≈28（不是小游戏的 17）
        //    → title='' + totalNodes>=22 时放行（主界面加载中），避免误踢
        val qqSpecialReason = if (pkg == "com.tencent.mobileqq") {
            when {
                selectedTitle == "QQ" && qqTotalNodes in 1..22 -> "🚫 QQ 小游戏（节点数=$qqTotalNodes）"
                selectedTitle == "QQ" && qqTotalNodes > 35 -> "🚫 QQ 游戏中心（节点数=$qqTotalNodes）"
                selectedTitle.isEmpty() && qqTotalNodes in 1..21 -> "🚫 QQ 小游戏（空标题，节点数=$qqTotalNodes）"
                // title='' 但 totalNodes>=22 → 主界面加载中，放行（覆盖 shouldBlock 的空标题误判）
                else -> null
            }
        } else null

        // QQ 启动时 title='' 是过渡状态，totalNodes>=22 说明是主界面加载中，强制放行
        val qqOverride = pkg == "com.tencent.mobileqq" && selectedTitle.isEmpty() && qqTotalNodes >= 22
        val reason = if (qqOverride) null else (qqSpecialReason ?: GameBlockMatcher.shouldBlock(pkg, cls, prefs, selectedTitle))

        if (reason != null) {
            pauseJustExpired = false  // 暂停到期首次检测完成
            val last = lastBlockTime[pkg] ?: 0L
            if (SystemClock.elapsedRealtime() - last < BLOCK_THROTTLE_MS) {
                Log.w(TAG, "节流中，直接 goHome: $reason")
                goHome()
                return
            }
            lastBlockTime[pkg] = SystemClock.elapsedRealtime()
            lastBlockedPkg = pkg
            lastBlockedTitle = selectedTitle
            lastBlockTimeMs = SystemClock.elapsedRealtime()
            Log.w(TAG, "🎯 命中拦截: $reason ⬅️ cls=$cls pkg=$pkg title='$selectedTitle'")
            goHomeAndToast("系统维护中，该功能暂时不可用")
        } else {
            // 放行时清空 lastBlocked 状态，防止下次同包误持续拦截
            pauseJustExpired = false  // 暂停到期首次检测完成
            lastBlockedPkg = null
            lastBlockedTitle = ""
            Log.d(TAG, "未命中拦截: pkg=$pkg cls=$cls title='$selectedTitle'")
        }
    }

    private fun goHome() {
        val inst = GameBlockAccessibilityService.getServiceInstance()
        if (inst != null) {
            Log.i(TAG, "goHome via 无障碍 performGlobalAction")
            runCatching { inst.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
                .onFailure { Log.e(TAG, "performGlobalAction 失败: ${it.message}") }
        } else {
            Log.i(TAG, "goHome via startActivity(HOME) fallback")
            runCatching {
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(home)
            }.onFailure { Log.e(TAG, "startActivity(HOME) 也失败了: ${it.message}") }
        }
    }

    private fun goHomeAndToast(msg: String) {
        goHome()
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "系统更新服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保障系统更新服务正常运行"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val ip = getLocalIp()
        val (title, text) = if (prefs.isPaused()) {
            "系统更新（暂停中）" to "更新检查已暂停 · 剩余 ${prefs.pauseRemainingSeconds() / 60 + 1} 分钟"
        } else {
            "系统更新" to "正在后台检查更新..."
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    /** 获取本机 WiFi IP（用于家长手机连接） */
    private fun getLocalIp(): String {
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        }
        return "127.0.0.1"
    }

    /**
     * 远程控制 HTTP server —— 家长通过同 WiFi 发暂停/恢复指令
     *
     * 接口（GET 请求，pwd 参数 = 家长锁密码）：
     *   /                          → 使用说明（无需密码）
     *   /status?pwd=xxx            → 当前状态 JSON
     *   /pause?minutes=10&pwd=xxx  → 暂停拦截 10 分钟（范围 1-120 分钟）
     *   /resume?pwd=xxx            → 立即恢复拦截
     */
    private class HttpCmdServer(
        private val service: ForegroundPollerService,
        private val port: Int
    ) {
        private val prefs = PrefsManager(service)
        private var serverSocket: ServerSocket? = null
        private var running = false
        private val thread = Thread { acceptLoop() }.apply { isDaemon = true }

        fun start() {
            running = true
            thread.start()
        }

        fun stop() {
            running = false
            runCatching { serverSocket?.close() }
        }

        private fun acceptLoop() {
            runCatching {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                Log.i(TAG, "HTTP 远程控制已启动: http://${service.getLocalIp()}:$port")
                while (running) {
                    val client = ss.accept()
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        Log.e(TAG, "handleClient error: ${e.message}")
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }.onFailure { Log.e(TAG, "HTTP server 启动失败: ${it.message}", it) }
        }

        private fun handleClient(client: Socket) {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // GET /path?query HTTP/1.1
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val fullPath = parts[1]
            val qIdx = fullPath.indexOf('?')
            val path = if (qIdx >= 0) fullPath.substring(0, qIdx) else fullPath
            val query = if (qIdx >= 0) fullPath.substring(qIdx + 1) else ""
            val params = parseQuery(query)

            val response = handleRequest(path, params, skipAuth = false)
            val out: OutputStream = client.getOutputStream()
            out.write(response.toByteArray())
            out.flush()
        }

        // MQTT 通过 topic 里的密码哈希认证过了，这里跳过密码校验
        fun handleRequestPublic(path: String, params: Map<String, String>): String = handleRequest(path, params, skipAuth = true)

        private fun handleRequest(path: String, params: Map<String, String>, skipAuth: Boolean = false): String {
            val pwd = params["pwd"] ?: ""
            val authed = skipAuth || pwd == prefs.parentPassword
            return when (path) {
                "/", "/help" -> buildResponse(200, """{"msg":"荣耀应用管控远程控制","endpoints":{"/status?pwd=xxx":"查询状态","/pause?minutes=10&pwd=xxx":"暂停N分钟","/resume?pwd=xxx":"立即恢复","/block_install?enable=true&pwd=xxx":"禁止安装","/block_install?enable=false&pwd=xxx":"允许安装","/lock_now?pwd=xxx":"永久锁屏（重启也锁，需手动解除）","/lock_for?minutes=30&pwd=xxx":"锁定N分钟，到点自动解除","/unlock?pwd=xxx":"一键解除锁屏+亮屏","/app_history?pwd=xxx":"查询APP打开历史（当前+最近记录）"}}""")
                "/status" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    val paused = prefs.isPaused()
                    val remain = prefs.pauseRemainingSeconds()
                    val blockInstall = prefs.blockInstallApps
                    val forceLocked = prefs.forceLocked
                    val lockRemain = prefs.forceLockRemainingSeconds()
                    buildResponse(200, """{"paused":$paused,"remain_seconds":$remain,"block_install_apps":$blockInstall,"force_locked":$forceLocked,"lock_remaining_seconds":$lockRemain}""")
                }
                "/pause" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    val minutes = (params["minutes"]?.toIntOrNull() ?: 10).coerceIn(1, 120)
                    val until = System.currentTimeMillis() + minutes * 60 * 1000L
                    prefs.pauseUntil = until
                    Log.i(TAG, "远程指令: 暂停拦截 $minutes 分钟，截止 $until")
                    val mgr = service.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    mgr.notify(NOTIF_ID, service.buildNotification())
                    // 立即推送新心跳（更新 broker retain，防止家长端看到旧状态）
                    service.mqttBridge?.immediatePublishStatus()
                    buildResponse(200, """{"ok":true,"paused_until":$until,"minutes":$minutes}""")
                }
                "/resume" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    prefs.pauseUntil = 0
                    Log.i(TAG, "远程指令: 立即恢复拦截")
                    val mgr = service.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    mgr.notify(NOTIF_ID, service.buildNotification())
                    service.mqttBridge?.immediatePublishStatus()
                    buildResponse(200, """{"ok":true,"paused":false}""")
                }
                "/lock_now" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    val ok = AdminReceiver.lockNow(service)
                    if (ok) {
                        prefs.forceLocked = true
                        prefs.lockUntilAt = 0L  // 0 = 永久，永不自动解除
                        service.lastForceLockMs = System.currentTimeMillis()
                        Log.i(TAG, "远程指令: 立即永久锁屏（强制模式）")
                        service.mqttBridge?.immediatePublishStatus()
                        buildResponse(200, """{"ok":true,"force_locked":true,"lock_remaining_seconds":-1,"msg":"已永久锁屏（重启也锁，只能家长手动解除）"}""")
                    } else {
                        buildResponse(500, """{"error":"锁屏失败（DeviceAdmin 未激活）"}""")
                    }
                }
                "/lock_for" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    val minutes = (params["minutes"]?.toIntOrNull() ?: 30).coerceIn(1, 1440)
                    val until = System.currentTimeMillis() + minutes * 60 * 1000L
                    val ok = AdminReceiver.lockNow(service)
                    if (ok) {
                        prefs.forceLocked = true
                        prefs.lockUntilAt = until
                        service.lastForceLockMs = System.currentTimeMillis()
                        Log.i(TAG, "远程指令: 定时锁定 $minutes 分钟（到 $until 自动解除）")
                        service.mqttBridge?.immediatePublishStatus()
                        buildResponse(200, """{"ok":true,"force_locked":true,"minutes":$minutes,"lock_until_at":$until,"msg":"已锁定 ${minutes} 分钟，到点自动解除（即使关机，开机也会自动解除）"}""")
                    } else {
                        buildResponse(500, """{"error":"锁屏失败（DeviceAdmin 未激活）"}""")
                    }
                }
                "/unlock" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    prefs.forceLocked = false
                    prefs.lockUntilAt = 0L
                    AdminReceiver.wakeUpScreen(service)
                    Log.i(TAG, "远程指令: 解除强制锁屏模式")
                    service.mqttBridge?.immediatePublishStatus()
                    buildResponse(200, """{"ok":true,"force_locked":false,"msg":"已解除强制锁屏（孩子可正常使用）"}""")
                }
                "/block_install" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    val enable = params["enable"].toBoolean()
                    // 更新持久化 + DeviceOwner 策略
                    prefs.blockInstallApps = enable
                    AdminReceiver.setBlockInstallPolicy(service, enable)
                    val label = if (enable) "禁止安装已开启" else "允许安装已开启"
                    Log.i(TAG, "远程指令: $label")
                    service.mqttBridge?.immediatePublishStatus()
                    buildResponse(200, """{"ok":true,"block_install_apps":$enable,"msg":"$label"}""")
                }
                "/list_apps" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    listInstalledApps()
                }
                "/hide_app" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    val pkg = params["pkg"] ?: return buildResponse(400, """{"error":"缺少 pkg 参数"}""")
                    if (pkg == service.packageName) return buildResponse(400, """{"error":"不能隐藏管控自身"}""")
                    val ok = AdminReceiver.hideApp(service, pkg)
                    if (ok) {
                        val label = getAppLabel(pkg)
                        Log.i(TAG, "远程指令: 隐藏 APP $pkg ($label)")
                        buildResponse(200, """{"ok":true,"package":"$pkg","label":"$label","hidden":true,"msg":"已隐藏 $label（从桌面消失）"}""")
                    } else {
                        buildResponse(500, """{"error":"隐藏失败，可能不是 DeviceOwner"}""")
                    }
                }
                "/show_app" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    val pkg = params["pkg"] ?: return buildResponse(400, """{"error":"缺少 pkg 参数"}""")
                    val ok = AdminReceiver.showApp(service, pkg)
                    if (ok) {
                        val label = getAppLabel(pkg)
                        Log.i(TAG, "远程指令: 显示 APP $pkg ($label)")
                        buildResponse(200, """{"ok":true,"package":"$pkg","label":"$label","hidden":false,"msg":"已显示 $label"}""")
                    } else {
                        buildResponse(500, """{"error":"显示失败"}""")
                    }
                }
                "/update_app" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    Log.i(TAG, "远程指令: 推送 APP 更新（家长触发）")
                    service.triggerSilentUpdate()
                    buildResponse(200, """{"ok":true,"msg":"开始下载更新，请稍候..."}""")
                }
                "/app_history" -> {
                    if (!authed) return buildResponse(403, """{"error":"密码错误"}""")
                    Log.i(TAG, "远程指令: 查询 APP 打开历史")
                    buildResponse(200, AppHistoryStore.buildJson(service))
                }
                else -> buildResponse(404, """{"error":"unknown path: $path"}""")
            }
        }

        /** 获取 APP 的显示名称（包名找不到则返回包名本身） */
        private fun getAppLabel(packageName: String): String {
            return runCatching {
                val pm = service.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }

        /** 列出所有第三方 APP + 被隐藏状态，返回 JSON */
        private fun listInstalledApps(): String {
            // v22: 包管理器在 binder 压力下可能返回不完整结果（"only received X of 333"），最多重试 3 次
            var lastErr: String? = null
            repeat(3) { attempt ->
                val result = runCatching {
                    val pm = service.packageManager
                    val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                        .filter { !isSystemApp(it.packageName, pm) }
                        .filter { it.packageName != service.packageName }
                        .map { app ->
                            // 🔧 JSONObject.quote 转义所有特殊字符（引号/反斜杠/换行/控制字符），
                            // 防止某个 APP 名称含特殊符号时毁掉整个 JSON，导致家长端解析失败
                            val labelJson = runCatching { JSONObject.quote(pm.getApplicationLabel(app).toString()) }
                                .getOrDefault(JSONObject.quote(app.packageName))
                            val hidden = AdminReceiver.isAppHidden(service, app.packageName)
                            """{"package":"${app.packageName}","label":$labelJson,"hidden":$hidden}"""
                        }
                    val hiddenCount = apps.count { it.contains("\"hidden\":true") }
                    val json = """{"ok":true,"count":${apps.size},"hidden_count":$hiddenCount,"apps":[${apps.joinToString(",")}]}"""
                    buildResponse(200, json)
                }
                result.onSuccess { return it }
                result.onFailure {
                    lastErr = it.message
                    Log.w(TAG, "listInstalledApps 第 ${attempt + 1} 次失败: ${it.message}")
                    Thread.sleep(300)
                }
            }
            return buildResponse(500, """{"error":"列出APP失败: $lastErr"}""")
        }

        /** 粗略判断是否系统 APP */
        private fun isSystemApp(packageName: String, pm: android.content.pm.PackageManager): Boolean {
            return runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }.getOrDefault(false)
        }

        private fun buildResponse(code: Int, json: String): String {
            val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "OK" }
            return "HTTP/1.1 $code $status\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n$json"
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            return query.split("&").mapNotNull {
                val idx = it.indexOf('=')
                if (idx > 0) {
                    val key = it.substring(0, idx)
                    val rawValue = it.substring(idx + 1)
                    val value = runCatching {
                        java.net.URLDecoder.decode(rawValue, "UTF-8")
                    }.getOrDefault(rawValue)
                    key to value
                } else null
            }.toMap()
        }
    }
}
