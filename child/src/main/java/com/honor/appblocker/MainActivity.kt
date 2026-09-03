package com.honor.appblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils.SimpleStringSplitter
import android.text.method.PasswordTransformationMethod
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.honor.appblocker.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 主界面 - 新手一键模式 + 家长锁
 *
 * 设计原则（给孩子学习用的手机，越简单越好）：
 * 1. 顶部「🚀 一键启动全防护」大按钮 - 点一下自动完成所有可自动化的操作
 * 2. 管控开关默认全部 ON + 默认锁定不可点 - 孩子找不到办法关掉
 * 3. 家长解锁：点击顶部「🛡️ 荣耀应用管控」标题 → 输入密码 → 解锁管控开关修改权限
 *    （默认密码 1234，家长解锁后可点「修改密码」按钮修改）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    // v19: 当前安装版本，例如 "v3.0（19）"，直接取自系统 PackageManager，永远和实际安装一致
    private val appVersion: String by lazy {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") pInfo.versionCode
            }
            "v${pInfo.versionName}（$code）"
        } catch (e: Exception) { "v3.0" }
    }

    // ========= 家长锁：点击标题 → 输入密码解锁 =========
    private var parentUnlocked = false

    // ========= UI 自动刷新定时器（家长端远程控制后同步开关） =========
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                refreshStatus()
                mainHandler.postDelayed(this, 3000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)

        // ===== 启动自愈：=====
        // 维护窗口超时：家长更新APP后忘开禁装开关 → 静默自动恢复（不弹提示，避免孩子发现）
        if (!prefs.blockInstallApps && prefs.isInstallBlockMaintenanceExpired()) {
            prefs.blockInstallApps = true
        }

        setupViews()
        setupListeners()

        // v3.0: 标题栏附加 DeviceId（家长端需要这个 ID 才能跨网络控制）
        val deviceId = MqttConfig.defaultChildDeviceId(this)
        binding.tvHeaderTitle.text = "🛡️ 荣耀应用更新\n📡 DeviceId: $deviceId"

        // v19: 界面显示当前版本号（伪装页显示"组件版本"，家长解锁后的真界面也显示）
        binding.tvStealthVersion.text = "组件版本 $appVersion"

        // DeviceOwner策略漂移自愈（重启/ROM升级可能清除限制）+ 开关状态与系统策略同步
        AdminReceiver.enforceAllPolicies(this)

        refreshStatus()
        // v16 伪装模式：默认只显示"系统检查完成"假页面，输密码后才显示真实管控界面
        applyStealthMode()

        // v3.0 自动更新检查：延迟 10 秒（孩子端启动稍重，等 MQTT 和守护服务起来后再查）
        mainHandler.postDelayed({ checkUpdate() }, 10000)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        mainHandler.removeCallbacks(uiRefreshRunnable)
        mainHandler.postDelayed(uiRefreshRunnable, 3000L)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(uiRefreshRunnable)
    }

    // ============= 初始化视图与开关 =============

    private fun setupViews() {
        // 仅首次启动强制全开（新手零配置）；之后尊重家长手动关闭的开关
        // （避免家长更新APP临时关闭开关后，一打开App开关被强制改回）
        if (!prefs.isInitialConfigDone()) {
            ensureAllSwitchesOn()
            prefs.markInitialConfigDone()
        }
        binding.switchBlockInstall.isChecked = prefs.blockInstallApps
        binding.switchBlockWechat.isChecked = prefs.blockWechatGame
        binding.switchBlockPopup.isChecked = prefs.blockPopupGame
        binding.switchBlockBrowser.isChecked = prefs.blockBrowserGame
        binding.switchBlockShortVideo.isChecked = prefs.blockShortVideoGame
        binding.switchBlockFactoryReset.isChecked = prefs.blockFactoryReset
    }

    /** 确保所有管控开关为开启状态（首次启动自动打开） */
    private fun ensureAllSwitchesOn() {
        if (!prefs.blockInstallApps) prefs.blockInstallApps = true
        if (!prefs.blockWechatGame) prefs.blockWechatGame = true
        if (!prefs.blockPopupGame) prefs.blockPopupGame = true
        if (!prefs.blockBrowserGame) prefs.blockBrowserGame = true
        if (!prefs.blockShortVideoGame) prefs.blockShortVideoGame = true
        if (!prefs.blockFactoryReset) prefs.blockFactoryReset = true
    }

    // ============ v3.0 自动更新（孩子端 DeviceOwner 版） ============
    // 关键差异：安装前临时关闭 DISALLOW_INSTALL_APPS + DISALLOW_UNINSTALL_APPS
    // 新版本 App 启动时 enforceAllPolicies() 会自动恢复，无需手动再开

    // v21: 一条更新线路的查询结果
    private data class UpdateInfo(
        val code: Int,
        val name: String,
        val apkUrl: String,
        val changelog: String,
        val source: String
    )

    /**
     * v21: 多线路并发查询 version.json，取版本号最高的结果。
     * jsDelivr 各地缓存节点刷新时间不一（实测同一时刻三个节点分别返回 17/18/19），
     * 并发查全部线路并取最高版本号，任何一条线路拿到新版就提示，CDN 缓存延迟不再影响更新。
     */
    private fun fetchLatestUpdate(appKey: String): UpdateInfo? {
        val urls = listOf(
            "https://cdn.jsdelivr.net/gh/Panyutian/Panyutian_app@main/version.json",
            "https://fastly.jsdelivr.net/gh/Panyutian/Panyutian_app@main/version.json",
            "https://gcore.jsdelivr.net/gh/Panyutian/Panyutian_app@main/version.json",
            "https://jsd.cdn.zzko.cn/gh/Panyutian/Panyutian_app@main/version.json",
            "https://ghfast.top/https://raw.githubusercontent.com/Panyutian/Panyutian_app/main/version.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/Panyutian/Panyutian_app/main/version.json",
            "https://raw.githubusercontent.com/Panyutian/Panyutian_app/main/version.json"
        )
        val results = java.util.Collections.synchronizedList(mutableListOf<UpdateInfo>())
        val latch = java.util.concurrent.CountDownLatch(urls.size)
        for (u in urls) {
            Thread {
                try {
                    val conn = URL("$u?ts=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.useCaches = false
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.setRequestProperty("Pragma", "no-cache")
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val obj = JSONObject(body).getJSONObject(appKey)
                    results.add(UpdateInfo(
                        code = obj.getInt("versionCode"),
                        name = obj.getString("versionName"),
                        apkUrl = obj.getString("apkUrl"),
                        changelog = obj.optString("changelog", ""),
                        source = u
                    ))
                } catch (e: Exception) {
                    android.util.Log.e("ChildUpdate", "线路失败: $u (${e.message})")
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return results.maxByOrNull { it.code }
    }

    private fun checkUpdate() {
        thread {
            val info = fetchLatestUpdate("child")
            if (info == null) {
                android.util.Log.e("ChildUpdate", "所有线路都失败，跳过检查")
                return@thread
            }
            val currentCode = runCatching {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            }.getOrDefault(0)

            android.util.Log.w("ChildUpdate", "local=$currentCode best=${info.code} source=${info.source}")

            if (info.code > currentCode) {
                mainHandler.post {
                    val msg = buildString {
                        append("发现新的系统更新。\n\n")
                        append("当前版本：v${info.name}（$currentCode）\n")
                        append("最新版本：v${info.name}（${info.code}）")
                        if (info.changelog.isNotBlank()) append("\n\n更新内容：${info.changelog}")
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🔄 系统更新")
                        .setMessage(msg)
                        .setPositiveButton("立即安装") { _, _ -> downloadAndInstall(info.apkUrl, info.name) }
                        .setNegativeButton("稍后") { _, _ -> }
                        .show()
                }
            }
        }
    }

    private fun downloadAndInstall(apkUrl: String, versionName: String) {
        // 国内网络镜像
        val realUrl = if (apkUrl.contains("github.com")) {
            "https://ghfast.top/$apkUrl"
        } else apkUrl

        thread {
            var tempFile: File? = null
            try {
                val url = URL(realUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()

                val input = conn.inputStream
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                tempFile = File(downloadsDir, "HonorAppBlocker-child-update.apk")
                if (tempFile.exists()) tempFile.delete()

                tempFile.outputStream().use { output -> input.copyTo(output) }
                input.close()
                conn.disconnect()

                mainHandler.post {
                    android.util.Log.w("ChildUpdate", "下载完成，准备安装")
                    installApk(tempFile!!)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "❌ 自动更新下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 孩子端 DeviceOwner 版安装：先临时关闭禁装策略，再安装 */
    private fun installApk(apkFile: File) {
        // 🔧 关键：DeviceOwner 禁止了 DISALLOW_INSTALL_APPS 和 DISALLOW_UNINSTALL_APPS
        // 自更新需要先临时关闭（因为同包名更新 → 系统内部先卸载旧版再装新版）
        AdminReceiver.setBlockInstallPolicy(this, false)
        android.util.Log.w("ChildUpdate", "已临时关闭禁装/禁卸策略，准备安装")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val authority = "${packageName}.fileprovider"
        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, authority, apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        startActivity(intent)

        // 新版本 App 启动时 → onCreate → AdminReceiver.enforceAllPolicies()
        // → 自动根据 prefs.blockInstallApps 恢复 DISALLOW_INSTALL_APPS 策略 ✅
    }

    private fun setupListeners() {
        // ========= 核心1：一键启动全防护大按钮（新手 1 键搞定） =========
        binding.btnOneKeyProtect.setOnClickListener {
            performOneKeyProtect()
        }

        // ========= 核心2：家长锁 - 点击顶部标题 → 输入密码解锁 =========
        binding.tvHeaderTitle.setOnClickListener {
            if (parentUnlocked) {
                // 已解锁时再点标题：直接重新锁定（回到伪装页）
                parentUnlocked = false
                applyParentLockState()
                applyStealthMode()
                Toast.makeText(this, "🔒 已重新锁定", Toast.LENGTH_SHORT).show()
            } else {
                showParentUnlockDialog()
            }
        }

        // ========== 以下为管控开关监听（仅家长解锁状态下有效） ==========
        // 禁止安装APP
        binding.switchBlockInstall.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockInstallApps = isChecked
            if (isChecked) {
                if (AdminReceiver.isDeviceOwner(this) || AdminReceiver.isAdminActive(this)) {
                    AdminReceiver.setBlockInstallPolicy(this, true)
                } else if (parentUnlocked) {
                    // 家长操作时才提示，避免孩子操作时泄露
                    Toast.makeText(this, R.string.toast_need_device_owner, Toast.LENGTH_LONG).show()
                }
            } else {
                AdminReceiver.setBlockInstallPolicy(this, false)
            }
        }

        // 禁止微信游戏
        binding.switchBlockWechat.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockWechatGame = isChecked
            if (isChecked && !GameBlockAccessibilityService.isRunning() && parentUnlocked) {
                Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show()
            }
        }

        // 禁止弹窗游戏
        binding.switchBlockPopup.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockPopupGame = isChecked
            if (isChecked && !GameBlockAccessibilityService.isRunning() && parentUnlocked) {
                Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show()
            }
        }

        // 禁止浏览器H5网页游戏
        binding.switchBlockBrowser.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockBrowserGame = isChecked
            if (isChecked && !GameBlockAccessibilityService.isRunning() && parentUnlocked) {
                Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show()
            }
        }

        // 禁止QQ/抖音/快手等App内嵌小游戏
        binding.switchBlockShortVideo.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockShortVideoGame = isChecked
            if (isChecked && !GameBlockAccessibilityService.isRunning() && parentUnlocked) {
                Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_LONG).show()
            }
        }

        // 禁止恢复出厂设置
        binding.switchBlockFactoryReset.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockFactoryReset = isChecked
            if (isChecked) {
                if (AdminReceiver.isDeviceOwner(this) || AdminReceiver.isAdminActive(this)) {
                    AdminReceiver.setBlockFactoryResetPolicy(this, true)
                } else if (parentUnlocked) {
                    Toast.makeText(this, R.string.toast_need_device_owner, Toast.LENGTH_LONG).show()
                }
            } else {
                AdminReceiver.setBlockFactoryResetPolicy(this, false)
            }
        }

        // 按钮：开启无障碍服务
        binding.btnEnableA11y.setOnClickListener {
            if (GameBlockAccessibilityService.isRunning()) {
                Toast.makeText(this, "无障碍服务已运行，无需重复开启", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openAccessibilitySettings()
        }

        // 按钮：重启守护服务
        binding.btnRebootGuard.setOnClickListener {
            try {
                val svc = Intent(this, GuardService::class.java)
                stopService(svc)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
                else startService(svc)
                Toast.makeText(this, "守护服务已重启", Toast.LENGTH_SHORT).show()
                refreshStatus()
            } catch (e: Exception) {
                Toast.makeText(this, "重启失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 按钮：解除DeviceOwner（必须家长解锁才能按）
        binding.btnRemoveOwner.setOnClickListener {
            if (!parentUnlocked) {
                Toast.makeText(this, "🔒 请先点击顶部标题输入密码解锁", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            showConfirmRemoveDialog()
        }

        // 按钮：修改家长解锁密码（必须家长解锁才能按）
        binding.btnChangePassword.setOnClickListener {
            if (!parentUnlocked) {
                Toast.makeText(this, "🔒 请先点击顶部标题输入密码解锁", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            showChangePasswordDialog()
        }

        // 按钮：临时开启/关闭开发者选项（家长更新APP时用，平时隐藏防孩子关USB调试）
        binding.btnToggleDevOptions.setOnClickListener {
            if (!parentUnlocked) {
                Toast.makeText(this, "🔒 请先点击顶部标题输入密码解锁", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            toggleDevOptions()
        }

    }

    /** 临时开启/关闭开发者选项菜单（家长模式下可用） */
    private fun toggleDevOptions() {
        if (!AdminReceiver.isDeviceOwner(this)) {
            Toast.makeText(this, "⚠️ 需要 DeviceOwner 权限才能控制开发者选项", Toast.LENGTH_LONG).show()
            return
        }
        val current = try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        } catch (e: Exception) {
            1
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val newValue = if (current == 1) 0 else 1
        try {
            dpm.setGlobalSetting(
                ComponentName(this, AdminReceiver::class.java),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                newValue.toString()
            )
            val msg = if (newValue == 1) "🛠 开发者选项已临时开启（设置里可见了）"
                      else "✅ 开发者选项已重新隐藏"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ========= 一键启动全防护：能自动做的全部一次性做完 =========
    private fun performOneKeyProtect() {
        val msgs = mutableListOf<String>()

        // --- 步骤 ①：自动开启所有 6 个管控开关 ---
        ensureAllSwitchesOn()
        binding.switchBlockInstall.isChecked = true
        binding.switchBlockWechat.isChecked = true
        binding.switchBlockPopup.isChecked = true
        binding.switchBlockBrowser.isChecked = true
        binding.switchBlockShortVideo.isChecked = true
        binding.switchBlockFactoryReset.isChecked = true
        msgs.add("✅ 步骤①：已开启全部管控开关")

        // --- 步骤 ②：DeviceOwner 策略立即生效（如果有管理员权限） ---
        if (AdminReceiver.isDeviceOwner(this) || AdminReceiver.isAdminActive(this)) {
            AdminReceiver.enforceAllPolicies(this)
            msgs.add("✅ 步骤③：DeviceOwner 禁装策略已激活")
        }

        // --- 步骤 ③：启动守护服务（防止系统回收） ---
        try {
            val svc = Intent(this, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            msgs.add("✅ 后台守护服务已启动")
        } catch (_: Exception) { }

        // --- 步骤 ③.5：启动轮询兜底服务（UsageStats）---
        try {
            ForegroundPollerService.start(this)
            msgs.add("✅ 游戏轮询守护已启动（绕开无障碍管道卡死的兜底方案）")
        } catch (_: Exception) { }

        // --- 步骤 ④：无障碍 + UsageStats + DeviceOwner 状态 ---
        val a11yOk = GameBlockAccessibilityService.isRunning()
        val usageOk = isUsageStatsEnabled()
        val doOk = AdminReceiver.isDeviceOwner(this)

        AlertDialog.Builder(this)
            .setTitle("一键启动全防护")
            .setMessage(
                buildString {
                    append(msgs.joinToString("\n"))
                    append("\n\n")
                    if (!usageOk) {
                        append("⚠️ 还需手动完成（绕开无障碍管道卡死的兜底方案）：\n")
                        append("   ② 授权【有权查看使用情况】（游戏轮询必需）\n")
                    }
                    if (!a11yOk) {
                        append("⚠️ 还需手动完成：\n")
                        append("   ③ 开启【无障碍服务】（双重保险）\n")
                    }
                    if (!doOk) {
                        append("⚠️ 还需手动完成：\n")
                        append("   ④ 电脑ADB激活【DeviceOwner】（禁装APP必开）\n")
                        append("   → 可稍后点「查看激活步骤(ADB)」按钮查看\n")
                    }
                    if (usageOk && a11yOk && doOk) {
                        append("\n🎉 防护配置全部完成！孩子可以安心学习了。")
                    }
                }
            )
            .setPositiveButton("确定") { _, _ ->
                when {
                    !usageOk -> {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                    !a11yOk -> openAccessibilitySettings()
                    !doOk -> Toast.makeText(this, "⚠️ DeviceOwner 未激活，需用电脑 ADB 命令激活", Toast.LENGTH_LONG).show()
                }
                refreshStatus()
            }
            .show()
    }

    // ========= v16 伪装模式：真实界面与假页面切换 =========
    private fun applyStealthMode() {
        if (parentUnlocked) {
            binding.llRealContent.visibility = android.view.View.VISIBLE
            binding.llStealthPage.visibility = android.view.View.GONE
        } else {
            binding.llRealContent.visibility = android.view.View.GONE
            binding.llStealthPage.visibility = android.view.View.VISIBLE
            val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            binding.tvStealthTime.text = "上次检查：今天 $time"
        }
    }

    // ========= 应用家长锁状态（锁定/解锁6个开关 + 解除DeviceOwner按钮） =========
    private fun applyParentLockState() {
        val enabled = parentUnlocked
        binding.switchBlockInstall.isEnabled = enabled
        binding.switchBlockWechat.isEnabled = enabled
        binding.switchBlockPopup.isEnabled = enabled
        binding.switchBlockBrowser.isEnabled = enabled
        binding.switchBlockShortVideo.isEnabled = enabled
        binding.switchBlockFactoryReset.isEnabled = enabled
        if (enabled) {
            binding.tvParentLockHint.text = "🔓 已解锁（家长模式）"
            binding.tvParentLockHint.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            // 锁定态显示密码最后修改时间，家长可一眼发现孩子是否私自改过密码
            val changedAt = prefs.parentPasswordChangedAt
            binding.tvParentLockHint.text = if (changedAt > 0L) {
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(changedAt))
                "🔒 已锁定 · 密码修改于 $time"
            } else {
                "🔒 已锁定"
            }
            binding.tvParentLockHint.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }
    }

    // ============= 状态刷新（状态卡片 + 3步进度条） =============

    private fun refreshStatus() {
        // --- DeviceOwner ---
        val isDO = AdminReceiver.isDeviceOwner(this)
        val isAdmin = AdminReceiver.isAdminActive(this)
        binding.tvStatusDeviceOwner.apply {
            when {
                isDO -> {
                    text = getString(R.string.status_granted)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                }
                isAdmin -> {
                    text = "⚠️ 普通管理员"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_orange))
                }
                else -> {
                    text = getString(R.string.status_not_granted)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                }
            }
        }

        // --- 无障碍服务 ---
        val a11yRunning = GameBlockAccessibilityService.isRunning()
        binding.tvStatusAccessibility.apply {
            if (a11yRunning) {
                text = getString(R.string.status_enabled)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
            } else {
                text = getString(R.string.status_disabled)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
            }
        }

        // --- 守护服务 ---
        val guardRunning = isServiceRunning(GuardService::class.java)
        binding.tvStatusGuard.apply {
            if (guardRunning) {
                text = getString(R.string.status_active)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
            } else {
                text = getString(R.string.status_inactive)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
            }
        }

        // --- 头部 3 步进度条 ---
        // ① 开关
        val allOn = prefs.blockInstallApps && prefs.blockWechatGame && prefs.blockPopupGame &&
                prefs.blockBrowserGame && prefs.blockShortVideoGame && prefs.blockFactoryReset
        binding.tvStep1.apply {
            if (allOn) {
                text = "① 开启所有管控开关   ✅ 完成"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
            } else {
                text = "① 开启所有管控开关   ❌ 未完成"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
            }
        }
        // ② 无障碍
        binding.tvStep2.apply {
            if (a11yRunning) {
                text = "② 开启无障碍服务     ✅ 完成"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
            } else {
                text = "② 开启无障碍服务     ❌ 未完成"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
            }
        }
        // ③ DeviceOwner
        binding.tvStep3.apply {
            if (isDO) {
                text = "③ 激活DeviceOwner（禁装APP）  ✅ 完成"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
            } else {
                text = "③ 激活DeviceOwner（禁装APP）  ❌ 未完成"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
            }
        }

        // --- 同步所有开关状态（家长端远程控制后刷新 UI） ---
        binding.switchBlockInstall.isChecked = prefs.blockInstallApps
        binding.switchBlockWechat.isChecked = prefs.blockWechatGame
        binding.switchBlockPopup.isChecked = prefs.blockPopupGame
        binding.switchBlockBrowser.isChecked = prefs.blockBrowserGame
        binding.switchBlockShortVideo.isChecked = prefs.blockShortVideoGame
        binding.switchBlockFactoryReset.isChecked = prefs.blockFactoryReset

        // --- v3.0 MQTT 连接状态更新到标题栏 ---
        val bridge = MqttBridge.instance
        val mqttStatus = when {
            bridge == null -> "⏳ 等待服务启动"
            bridge.connected -> "🟢 已连接 MQTT (${MqttConfig.brokerUrl.substringAfterLast("/")})"
            else -> "🔴 MQTT 重连中... ${MqttBridge.lastError.take(50)}"
        }
        val deviceId = MqttConfig.defaultChildDeviceId(this)
        binding.tvHeaderTitle.text = "🛡️ 荣耀应用管控\n📡 $deviceId\n$mqttStatus\n当前版本 $appVersion"

        // --- 应用家长锁 UI ---
        applyParentLockState()
    }

    private fun isServiceRunning(clazz: Class<*>): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == clazz.name }
    }

    // ============= 辅助方法 =============

    /** 打开无障碍服务设置页 */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(
                ":settings:show_fragment",
                "com.android.settings.accessibility.AccessibilitySettings"
            )
            startActivity(intent)
            AlertDialog.Builder(this)
                .setTitle("第②步：开启无障碍服务（必开）")
                .setMessage(
                    """
                    在打开的页面中：
                    ① 找到【已下载的应用】或【荣耀应用管控】
                    ② 点进去 → 打开【使用荣耀应用管控】开关 → 确定
                    ③ 返回本App即可自动生效

                    ⚠️ 重要（防止荣耀系统杀后台）：
                    手机管家 → 应用启动管理 → 将【荣耀应用管控】改为【手动管理】
                     → 3 项全部打勾（自启动 / 关联启动 / 后台活动）

                    完成后请点「我已开启」。
                    """.trimIndent()
                )
                .setPositiveButton("我已开启") { d, _ -> d.dismiss(); refreshStatus() }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
        }
    }

    /** 检测无障碍服务是否启用（系统层级的启用状态，不一定已连接） */
    @Suppress("unused")
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        val component = ComponentName(this, GameBlockAccessibilityService::class.java)
        while (splitter.hasNext()) {
            val str = splitter.next()
            runCatching {
                val enabledComp = ComponentName.unflattenFromString(str)
                if (enabledComp == component) return true
            }
        }
        return false
    }

    /** 检测 UsageStats 权限（"有权查看使用情况"）是否已授权 */
    private fun isUsageStatsEnabled(): Boolean {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60_000L
        return runCatching {
            usage.queryEvents(startTime, endTime)
            true
        }.getOrDefault(false)
    }

    // ============= 弹窗 =============

    /** 确认解除DeviceOwner */
    private fun showConfirmRemoveDialog() {
        if (!AdminReceiver.isDeviceOwner(this) && !AdminReceiver.isAdminActive(this)) {
            Toast.makeText(this, "当前未激活管理员权限，无需解除", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("确认解除管控？（仅家长可操作）")
            .setMessage(
                """
                解除后：
                ✗ 禁止安装APP策略将失效
                ✗ 可卸载本应用
                ✓ 无障碍服务仍需手动关闭

                解除成功后，您可在 设置 → 应用 → 卸载本应用。
                """.trimIndent()
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认解除") { _, _ ->
                val result = AdminReceiver.clearDeviceOwner(this)
                if (result) {
                    Toast.makeText(this, R.string.toast_removed_admin, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("自动解除失败")
                        .setMessage(
                            """
                            Android系统限制下DeviceOwner可能无法在应用内直接解除，请使用电脑ADB命令：

                            adb shell dpm remove-active-admin ${packageName}/.AdminReceiver

                            若仍失败，可在设置中选择「清除所有内容」即恢复出厂设置。
                            """.trimIndent()
                        )
                        .setPositiveButton("我知道了", null)
                        .show()
                }
                refreshStatus()
            }
            .show()
    }

    /** ============= 家长锁密码弹窗 ============= */

    /** 家长解锁密码输入弹窗（v16 伪装文案，不暴露用途） */
    private fun showParentUnlockDialog() {
        val input = layoutInflater.inflate(R.layout.view_password_input, null) as EditText
        input.hint = "请输入密码"
        AlertDialog.Builder(this)
            .setTitle("访问受限")
            .setMessage("请输入管理密码以继续")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val pwd = input.text.toString().trim()
                if (pwd == prefs.parentPassword) {
                    parentUnlocked = true
                    applyParentLockState()
                    applyStealthMode()
                    Toast.makeText(this, "🔓 已解锁", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ 密码错误", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** 修改家长密码弹窗（需先解锁 → 当前密码已在解锁时验证过，只需输入新密码） */
    private fun showChangePasswordDialog() {
        val newPwd = layoutInflater.inflate(R.layout.view_password_input, null) as EditText
        newPwd.hint = "新密码（≥6位，字母/数字/符号都可）"
        AlertDialog.Builder(this)
            .setTitle("🔑 修改家长密码")
            .setMessage("当前密码已验证，只需输入新密码即可")
            .setView(newPwd)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val new = newPwd.text.toString()
                when {
                    new.length < 6 -> {
                        Toast.makeText(this, "❌ 新密码至少需要 6 位字符", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        prefs.parentPassword = new
                        Toast.makeText(this, "✅ 密码修改成功，请牢记新密码", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }
}

/**
 * 星号密码隐藏：用 ReplacementTransformationMethod 把每个可见字符替换成 '*'
 * 这是 Android 原生支持的做法 —— 不丢失真实密码、只读显示、不影响 input.text 取值
 */
private val AsteriskTransformationMethod = object : android.text.method.ReplacementTransformationMethod() {
    private val original = charArrayOf(
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
        'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
        'u', 'v', 'w', 'x', 'y', 'z',
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
        'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
        'U', 'V', 'W', 'X', 'Y', 'Z',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '!', '@', '#', '$', '%', '^', '&', '*', '(', ')',
        '-', '_', '=', '+', '[', ']', '{', '}', '|', '\\',
        ';', ':', '"', '\'', '<', '>', ',', '.', '/', '?',
        ' ', '`', '~'
    )
    private val replaced = CharArray(original.size) { '*' }

    override fun getOriginal(): CharArray = original
    override fun getReplacement(): CharArray = replaced
}

