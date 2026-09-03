package com.honor.parent

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.honor.parent.databinding.ActivityParentBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 家长控制端 v3.0 —— 支持两种控制模式
 *
 * 【🌐 跨网络 MQTT】（默认）：家长/孩子两端只需有任何网络（WiFi/4G/5G），通过 MQTT Broker 中转
 * 【🏠 同 WiFi HTTP】（兜底）：家长/孩子在同一局域网，HTTP 直连孩子端 8080 端口
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentBinding
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("parent_prefs", Context.MODE_PRIVATE) }

    // v3.0 MQTT 桥接
    private var mqttBridge: MqttBridge? = null

    // 缓存当前目标（HTTP 模式存 IP，MQTT 模式存 DeviceId）
    private var lastTarget = ""
    private var lastPwd = ""
    private var currentMode = MODE_MQTT
    private var lastStatusText = ""  // 状态卡片去重缓存
    // 本地倒计时基准（心跳 30 秒一次，用这个在家长端精确到秒显示）
    private var pauseRemainBaseSec = 0
    private var pauseRemainBaseAtMs = 0L
    private var pauseWasPaused = false
    private var lockRemainBaseSec = 0
    private var lockRemainBaseAtMs = 0L
    private var lockWasLocked = false
    private var lockWasForever = false
    private var currentBlockInstall = true  // 缓存禁止安装开关状态（默认禁止）
    private var pauseExpireNotified = false  // 暂停倒计时归零后是否已主动拉过 status
    private var lockExpireNotified = false   // 锁屏倒计时归零后是否已主动拉过 status

    companion object {
        private const val MODE_MQTT = "mqtt"
        private const val MODE_HTTP = "http"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 加载保存的配置
        val savedMode = prefs.getString("mode", MODE_MQTT) ?: MODE_MQTT
        currentMode = savedMode
        val savedIp = prefs.getString("ip", "") ?: ""
        val savedPwd = prefs.getString("pwd", "") ?: ""
        val savedDeviceId = prefs.getString("deviceId", "") ?: ""

        binding.etIp.setText(savedIp)
        binding.etPwd.setText(savedPwd)
        binding.etDeviceId.setText(savedDeviceId)

        // DeviceId 锁定逻辑：有值默认锁定（避免误删），解锁后可编辑
        updateDeviceIdLockState(savedDeviceId.isNotEmpty() && !prefs.getBoolean("deviceIdUnlocked", false))

        // 密码框锁定逻辑：有值默认锁定
        updatePwdLockState(savedPwd.isNotEmpty() && !prefs.getBoolean("pwdUnlocked", false))

        // DeviceId 解锁按钮
        binding.btnUnlockDeviceId.setOnClickListener {
            val isLocked = !binding.etDeviceId.isEnabled
            if (isLocked) {
                updateDeviceIdLockState(false)
                prefs.edit().putBoolean("deviceIdUnlocked", true).apply()
                toast("🔓 DeviceId 已解锁")
            } else {
                updateDeviceIdLockState(true)
                prefs.edit().putBoolean("deviceIdUnlocked", false).apply()
                savePrefs()
                if (currentMode == MODE_MQTT) {
                    val id = binding.etDeviceId.text.toString().trim()
                    val pwd = binding.etPwd.text.toString()
                    if (id.isNotEmpty() && pwd.isNotEmpty()) mqttBridge?.subscribeChild(id, pwd)
                }
                toast("🔒 DeviceId 已锁定保存")
            }
        }

        // 密码框解锁按钮
        binding.btnUnlockPwd.setOnClickListener {
            val isLocked = !binding.etPwd.isEnabled
            if (isLocked) {
                updatePwdLockState(false)
                prefs.edit().putBoolean("pwdUnlocked", true).apply()
                toast("🔓 密码框已解锁")
            } else {
                updatePwdLockState(true)
                prefs.edit().putBoolean("pwdUnlocked", false).apply()
                savePrefs()
                toast("🔒 密码已锁定保存")
            }
        }

        // DeviceId 输入框 —— 输入完成后自动锁定（失去焦点时）
        binding.etDeviceId.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val text = (v as android.widget.EditText).text.toString().trim()
                if (text.isNotEmpty() && !prefs.getBoolean("deviceIdUnlocked", false)) {
                    updateDeviceIdLockState(true)
                    savePrefs()
                    val pwd = prefs.getString("pwd", "") ?: ""
                    if (currentMode == MODE_MQTT && pwd.isNotEmpty()) mqttBridge?.subscribeChild(text, pwd)
                }
            }
        }

        // 密码框 —— 输入完成后自动锁定
        binding.etPwd.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val text = (v as android.widget.EditText).text.toString()
                if (text.isNotEmpty() && !prefs.getBoolean("pwdUnlocked", false)) {
                    updatePwdLockState(true)
                    savePrefs()
                }
            }
        }

        // RadioGroup 模式切换
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            currentMode = if (checkedId == binding.rbMqtt.id) MODE_MQTT else MODE_HTTP
            updateModeUI()
            savePrefs()
            if (currentMode == MODE_MQTT && savedDeviceId.isNotEmpty()) {
                val pwd = prefs.getString("pwd", "") ?: ""
                if (pwd.isNotEmpty()) mqttBridge?.subscribeChild(savedDeviceId, pwd)
            }
        }
        // 设置默认选中
        if (currentMode == MODE_MQTT) binding.rbMqtt.isChecked = true else binding.rbHttp.isChecked = true
        updateModeUI()

        // 按钮绑定
        binding.btnPause.setOnClickListener { send("pause?minutes=${pauseMinutes()}") }
        binding.btnResume.setOnClickListener { send("resume") }
        binding.btnBlockInstall.setOnClickListener { send("block_install?enable=true") }
        binding.btnAllowInstall.setOnClickListener { send("block_install?enable=false") }
        binding.btnStatus.setOnClickListener { send("status") }
        binding.btnListApps.setOnClickListener { send("list_apps") }
        binding.btnAppHistory.setOnClickListener { send("app_history") }
        binding.btnLockNow.setOnClickListener { send("lock_now") }
        binding.btnLockFor.setOnClickListener { send("lock_for?minutes=${lockMinutes()}") }
        binding.btnUnlock.setOnClickListener { send("unlock") }

        // v3.0.1 家长远程推送孩子端更新
        binding.btnPushChildUpdate.setOnClickListener {
            send("update_app")
            toast("📡 已推送更新指令，孩子端将在后台下载并安装")
        }

        // v3.0 启动 MqttBridge
        initMqttBridge()

        // v3.0 自动更新检查（App 启动后 5 秒后台检查 GitHub Release）
        handler.postDelayed({ checkUpdate() }, 5000)
    }

    // ============ 自动更新 ============

    private fun checkUpdate() {
        thread {
            // 三条线路依次尝试：jsDelivr CDN → ghfast.top 国内镜像 → GitHub raw 直连
            // 一条不通自动换下一条（jsDelivr 国内部分网络屏蔽，不能只依赖一条）
            val urls = listOf(
                "https://cdn.jsdelivr.net/gh/Panyutian/Panyutian_app@main/version.json",
                "https://ghfast.top/https://raw.githubusercontent.com/Panyutian/Panyutian_app/main/version.json",
                "https://raw.githubusercontent.com/Panyutian/Panyutian_app/main/version.json"
            )
            var body: String? = null
            for (u in urls) {
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (body.isNotEmpty()) break
                } catch (e: Exception) {
                    android.util.Log.e("UpdateCheck", "线路失败: $u (${e.message})")
                }
            }
            if (body.isNullOrEmpty()) {
                android.util.Log.e("UpdateCheck", "所有线路都失败，跳过检查")
                return@thread
            }
            try {
                val json = JSONObject(body)
                val parent = json.getJSONObject("parent")
                val latestCode = parent.getInt("versionCode")
                val latestName = parent.getString("versionName")
                val apkUrl = parent.getString("apkUrl")
                val changelog = parent.optString("changelog", "")
                val currentCode = runCatching {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                }.getOrDefault(0)

                android.util.Log.w("UpdateCheck", "local=$currentCode remote=$latestCode")

                if (latestCode > currentCode) {
                    handler.post {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("🎉 新版本 v$latestName 可用")
                            .setMessage("发现新版本，是否立即更新？\n\n$changelog")
                            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(apkUrl, latestName) }
                            .setNegativeButton("稍后") { _, _ -> }
                            .show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UpdateCheck", "FAIL (silent)", e)
            }
        }
    }

    private fun downloadAndInstall(apkUrl: String, versionName: String) {
        // 国内网络镜像：GitHub Release 直连慢/被墙，自动走 ghfast.top
        val realUrl = if (apkUrl.contains("github.com")) {
            "https://ghfast.top/$apkUrl"
        } else apkUrl

        val msg = "⏳ 正在下载 v$versionName ..."
        binding.tvStatus.text = msg
        lastStatusText = msg

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
                tempFile = File(downloadsDir, "HonorAppBlocker-parent-update.apk")
                if (tempFile.exists()) tempFile.delete()

                tempFile.outputStream().use { output -> input.copyTo(output) }
                input.close()
                conn.disconnect()

                handler.post {
                    binding.tvStatus.text = "✅ 下载完成，正在安装..."
                    lastStatusText = "✅ 下载完成，正在安装 v$versionName..."
                    installApk(tempFile!!)
                }
            } catch (e: Exception) {
                handler.post {
                    binding.tvStatus.text = "❌ 下载失败：${e.message}"
                    lastStatusText = "❌ 下载失败：${e.message}"
                    toast("❌ 自动更新下载失败，请手动更新")
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttBridge?.stop()
    }

    private fun updateModeUI() {
        // MQTT 模式：显示 DeviceId + 在线状态；HTTP 模式：显示 IP
        if (currentMode == MODE_MQTT) {
            binding.llMqttInputs.visibility = View.VISIBLE
            binding.llHttpInputs.visibility = View.GONE
            binding.tvMqttDebug.visibility = View.VISIBLE  // MQTT 模式默认显示连接状态
        } else {
            binding.llMqttInputs.visibility = View.GONE
            binding.llHttpInputs.visibility = View.VISIBLE
            binding.tvMqttDebug.visibility = View.GONE
        }
    }

    private fun initMqttBridge() {
        val bridge = MqttBridge(this)
        mqttBridge = bridge

        // 🔧 关键：监听孩子端主动推的 status topic → 自动刷新 UI 缓存
        bridge.statusListener = MqttBridge.OnStatusUpdateListener { body ->
            handler.post {
                try { updateStatusCard(JSONObject(body)) } catch (_: Exception) {}
            }
        }

        bridge.start()

        // 自动订阅保存的 DeviceId（App 重启后 MQTT 重连不丢失订阅）
        handler.postDelayed({
            val savedDeviceId = prefs.getString("deviceId", "") ?: ""
            val savedPwd = prefs.getString("pwd", "") ?: ""
            if (savedDeviceId.isNotEmpty() && savedPwd.isNotEmpty()) {
                mqttBridge?.subscribeChild(savedDeviceId, savedPwd)
                // 自动发一次 status 命令拉最新状态
                sendCommand("status", savedDeviceId, savedPwd, silent = true)
            }
        }, 3000)

        // 定期刷新在线状态显示
        val statusRefresh = object : Runnable {
            override fun run() {
                updateOnlineStatus()
                // 每秒刷新倒计时（精确到秒）
                renderStatusCard()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(statusRefresh, 2000)  // 延迟2秒等连接建立
    }

    private fun updateOnlineStatus() {
        val bridge = mqttBridge ?: return
        val status = bridge.lastChildStatus

        // 调试信息
        binding.tvMqttDebug.text = bridge.debugStatus()

        if (status != null) {
            val now = System.currentTimeMillis()
            val elapsedSec = (now - status.ts) / 1000
            val onlineText = if (status.online && elapsedSec < 120) {
                "🟢 孩子手机在线（${elapsedSec}秒前心跳）${if (status.battery > 0) "🔋${status.battery}%" else ""}"
            } else if (status.ts > 0) {
                "🔴 孩子手机离线（最后心跳 ${(elapsedSec / 60).coerceAtLeast(1)} 分钟前）"
            } else {
                "🔴 未收到孩子端心跳"
            }
            binding.tvOnlineStatus.text = onlineText
            // 心跳只更新状态标记（暂停/恢复），绝不覆盖倒计时基准
            // 倒计时基准只在命令返回时设定——本地秒级倒计时稳定运行，不受 30 秒心跳影响
            if (status.statusHttpBody.isNotEmpty()) {
                runCatching {
                    val json = JSONObject(status.statusHttpBody)
                    currentBlockInstall = json.optBoolean("block_install_apps", currentBlockInstall)
                    val paused = json.optBoolean("paused", false)
                    val forceLocked = json.optBoolean("force_locked", false)
                    val lockRem = json.optInt("lock_remaining_seconds", 0)
                    val now = System.currentTimeMillis()

                    // 暂停状态：心跳只切换标记，不碰时间基准
                    // 基准只由命令返回（pause/status 命令的 handleSuccess）设定
                    if (paused != pauseWasPaused) {
                        pauseWasPaused = paused
                        // 状态切换时，如果切到暂停但本地没有基准，用心跳补一个兜底
                        if (paused && pauseRemainBaseAtMs == 0L) {
                            pauseRemainBaseSec = json.optInt("remain_seconds", 0)
                            pauseRemainBaseAtMs = now
                        }
                    }

                    // 锁屏状态：同样
                    if (forceLocked != lockWasLocked) {
                        lockWasLocked = forceLocked
                        lockWasForever = forceLocked && lockRem == -1
                        if (forceLocked && lockRem > 0 && lockRemainBaseAtMs == 0L) {
                            lockRemainBaseSec = lockRem
                            lockRemainBaseAtMs = now
                        }
                    } else if (!forceLocked) {
                        lockWasForever = false
                    }
                }
            }
        } else {
            binding.tvOnlineStatus.text = if (bridge.connected) {
                "⏳ 等待孩子端上线（连接正常）"
            } else {
                "⏳ MQTT 连接中..."
            }
        }
    }

    /** 调试标题栏点击切换 debug 信息显示 */
    private fun toggleDebug() {
        binding.tvMqttDebug.visibility = if (binding.tvMqttDebug.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun MqttBridge.debugStatus(): String = buildString {
        append("MQTT bridge=${if (connected) "🟢" else "🔴"}")
        append(" broker=${MqttConfig.brokerUrl.take(40)}")
        append(" loadedOss=${MqttConfig.loadedFromOss}")
        if (MqttConfig.brokerUrl.startsWith("tcp://")) {
            append("\n⚠️ 明文传输，开发期专用！")
            append("\n生产环境请改用自建 EMQX + SSL")
        }
    }

    // ============ 发送命令 ============

    private fun pauseMinutes(): String = binding.etMinutes.text.toString().trim().ifEmpty { "10" }
    private fun lockMinutes(): String = binding.etLockMinutes.text.toString().trim().ifEmpty { "30" }

    private fun validateAndSave(): Boolean {
        val pwd = binding.etPwd.text.toString()
        if (pwd.isEmpty()) { toast("请填写密码"); return false }
        lastPwd = pwd

        lastTarget = when (currentMode) {
            MODE_MQTT -> binding.etDeviceId.text.toString().trim()
            MODE_HTTP -> binding.etIp.text.toString().trim()
            else -> ""
        }
        if (lastTarget.isEmpty()) {
            val hint = if (currentMode == MODE_MQTT) "孩子手机 DeviceId" else "孩子手机 IP"
            toast("请填写 $hint")
            return false
        }
        savePrefs()

        // MQTT 模式：输入 DeviceId 后立即 subscribe 孩子端 Result + Status
        if (currentMode == MODE_MQTT) {
            mqttBridge?.subscribeChild(lastTarget, lastPwd)
        }
        return true
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("mode", currentMode)
            .putString("ip", binding.etIp.text.toString())
            .putString("pwd", binding.etPwd.text.toString())
            .putString("deviceId", binding.etDeviceId.text.toString())
            .apply()
    }

    private fun send(cmd: String) {
        if (!validateAndSave()) return
        sendCommand(cmd, lastTarget, lastPwd)
    }

    /** 统一命令发送入口——根据模式走 HTTP 或 MQTT */
    private fun sendCommand(cmd: String, target: String, pwd: String, silent: Boolean = false) {
        when (currentMode) {
            MODE_HTTP -> sendHttp(cmd, target, pwd, silent)
            MODE_MQTT -> sendMqtt(cmd, target, pwd, silent)
        }
    }

    // ============ HTTP 模式（v2.3 原有逻辑）============

    private fun sendHttp(cmd: String, ip: String, pwd: String, silent: Boolean) {
        val port = if (ip.contains(":")) "" else ":8080"
        val realUrl = if (cmd.contains("?")) {
            "http://$ip$port/$cmd&pwd=$pwd"
        } else {
            "http://$ip$port/$cmd?pwd=$pwd"
        }

        if (!silent) {
            val loadingText = "⏳ 正在请求...\n$realUrl"
            binding.tvStatus.text = loadingText
            lastStatusText = loadingText
            setButtonsEnabled(false)
        }

        thread {
            try {
                val conn = (URL(realUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000
                    requestMethod = "GET"; instanceFollowRedirects = true
                }
                val code = conn.responseCode
                val body = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText() ?: ""
                }.getOrDefault("")
                conn.disconnect()

                handler.post {
                    if (!silent) setButtonsEnabled(true)
                    if (code in 200..299) {
                        try { handleSuccess(cmd, body) } catch (e: Exception) {
                            val errText = "❌ 数据处理失败: ${e.message}"
                            binding.tvStatus.text = errText
                            lastStatusText = errText
                            toast(errText)
                        }
                    }
                    else if (!silent) {
                        val msg = try { JSONObject(body).optString("error", body.ifEmpty { "无响应体" }) } catch (e: Exception) { body }
                        val errText = "❌ 错误 $code\n$msg"
                        binding.tvStatus.text = errText
                        lastStatusText = errText
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (!silent) {
                        setButtonsEnabled(true)
                        val errText = buildString {
                            append("❌ 孩子手机不在线或已关机，稍后重试...\n")
                            append("（${e.javaClass.simpleName}: ${e.message ?: ""}）\n\n")
                            append("请检查：\n")
                            append("• 孩子手机是否开机并连上同一 WiFi\n")
                            append("• IP 是否正确（孩子端 App 标题栏可查看）\n")
                            append("• 孩子手机是否打开过荣耀应用管控 App（前台服务需启动）")
                        }
                        binding.tvStatus.text = errText
                        lastStatusText = errText
                        toast("❌ 孩子手机不在线，请稍后再试")
                    }
                }
            }
        }
    }

    // ============ MQTT 模式（v3.0 新增）============

    private fun sendMqtt(cmd: String, deviceId: String, pwd: String, silent: Boolean) {
        val bridge = mqttBridge
        if (bridge == null || !bridge.connected) {
            if (!silent) {
                val errText = "❌ MQTT 未连接，请检查网络"
                binding.tvStatus.text = errText
                lastStatusText = errText
                toast("MQTT 未连接，稍后重试")
            }
            return
        }

        // 构造 params map（从 cmd 字符串解析）
        val (cmdName, params) = parseCmdString(cmd)

        if (!silent) {
            // 🔧 同步更新 lastStatusText，防止 renderStatusCard 的去重逻辑跳过更新
            val loadingText = "⏳ MQTT 发送: $cmdName → $deviceId"
            binding.tvStatus.text = loadingText
            lastStatusText = loadingText
            setButtonsEnabled(false)
        }

        bridge.sendCmd(deviceId, cmdName, params, pwd) { ok, httpCode, body, error ->
            handler.post {
                if (!silent) setButtonsEnabled(true)
                if (ok && httpCode in 200..299) {
                    try { handleSuccess(cmdName, body) } catch (e: Exception) {
                        // 🔧 弹窗显示详细原因 + 数据片段，便于远程排查
                        val detail = "命令 [$cmdName] 数据处理出错:\n${e.message}\n\n数据开头 200 字符:\n${body.take(200)}"
                        lastStatusText = "❌ 数据处理失败: ${e.message}"
                        binding.tvStatus.text = lastStatusText
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("⚠️ 数据处理失败")
                            .setMessage(detail)
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                } else if (!silent) {
                    val msg = error.ifEmpty { "HTTP $httpCode" }
                    val errText = "❌ 失败: $msg"
                    binding.tvStatus.text = errText
                    lastStatusText = errText
                    toast("❌ $msg")
                } else {
                    // 🔧 silent 请求失败/超时 → 恢复 tvStatus 为缓存的状态卡片，不停在 "⏳"
                    renderStatusCard()
                }
            }
        }
    }

    /** 从 "pause?minutes=10" 解析出 ("pause", {"minutes"->"10"}) */
    private fun parseCmdString(cmd: String): Pair<String, Map<String, String>> {
        val qIdx = cmd.indexOf('?')
        val cmdName = if (qIdx >= 0) cmd.substring(0, qIdx) else cmd
        val params = mutableMapOf<String, String>()
        if (qIdx >= 0) {
            cmd.substring(qIdx + 1).split("&").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) params[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }
        return cmdName to params
    }

    // ============ 命令执行结果处理（HTTP 和 MQTT 共用）============

    private fun handleSuccess(cmd: String, body: String) {
        val json = JSONObject(body)

        when {
            cmd.startsWith("list_apps") -> renderAppList(json)
            cmd.startsWith("app_history") -> renderAppHistory(json)
            cmd.startsWith("hide_app") -> {
                val label = json.optString("label", "APP")
                toast("🫥 已隐藏 $label（从桌面消失）")
                renderAppListAfterAction()
            }
            cmd.startsWith("show_app") -> {
                val label = json.optString("label", "APP")
                toast("✨ 已显示 $label")
                renderAppListAfterAction()
            }
            cmd.startsWith("lock_now") -> toast("🔒 孩子手机已永久锁屏（每10秒自动锁+开机自锁，只能家长手动解除）")
            cmd.startsWith("lock_for") -> {
                val m = json.optInt("minutes", 0)
                toast("⏱ 已设定时锁定 ${m} 分钟，到点自动解除（即使关机，开机也自动解）")
            }
            cmd.startsWith("unlock") -> toast("🔓 已解除强制锁屏（孩子现在可以正常使用）")
            cmd.startsWith("pause") -> toast("✅ 已暂停拦截 ${json.optInt("minutes", 0)} 分钟")
            cmd.startsWith("resume") -> toast("✅ 已恢复拦截")
            cmd.startsWith("block_install") -> toast(if (json.optString("msg", "").contains("禁止")) "🚫 已禁止安装 APP" else "✅ 已允许安装 APP")
            cmd.startsWith("status") -> updateStatusCard(json)
        }

        // 🔧 关键修复：每个命令执行后立即恢复 tvStatus（从缓存渲染），不再停在 "⏳"
        // 同时发一个 status 请求拉最新状态（QoS 1 保证送达）
        if (!cmd.startsWith("status") && !cmd.startsWith("list_apps") && !cmd.startsWith("app_history")) {
            renderStatusCard()  // 立即从缓存恢复状态卡片，不等 status 结果
            if (lastTarget.isNotEmpty() && lastPwd.isNotEmpty()) {
                sendCommand("status", lastTarget, lastPwd, silent = true)
            }
        }
    }

    private fun updateStatusCard(json: JSONObject) {
        val paused = json.optBoolean("paused", false)
        val remain = json.optInt("remain_seconds", 0)
        val blockInstall = json.optBoolean("block_install_apps", true)
        val forceLocked = json.optBoolean("force_locked", false)
        val lockRemain = json.optInt("lock_remaining_seconds", 0)
        val now = System.currentTimeMillis()

        // 缓存所有状态（给每秒刷新用）
        currentBlockInstall = blockInstall
        pauseWasPaused = paused
        if (paused) {
            pauseRemainBaseSec = remain
            pauseRemainBaseAtMs = now
            pauseExpireNotified = false
        }
        lockWasLocked = forceLocked
        lockWasForever = forceLocked && lockRemain == -1
        if (forceLocked && lockRemain > 0) {
            lockRemainBaseSec = lockRemain
            lockRemainBaseAtMs = now
            lockExpireNotified = false
        } else if (!forceLocked) {
            lockExpireNotified = false
        }

        renderStatusCard()
    }

    /** 用本地倒计时渲染状态卡片（精确到秒） */
    private fun renderStatusCard() {
        val now = System.currentTimeMillis()

        // 暂停倒计时（本地算）
        val currentPauseRemain = if (pauseWasPaused) {
            (pauseRemainBaseSec - (now - pauseRemainBaseAtMs) / 1000).coerceAtLeast(0)
        } else 0
        // 本地倒计时到 0 → 自动切状态 + 主动拉一次孩子端确认（双保险）
        if (pauseWasPaused && currentPauseRemain <= 0 && pauseRemainBaseAtMs > 0) {
            pauseWasPaused = false
            if (!pauseExpireNotified) {
                pauseExpireNotified = true
                if (lastTarget.isNotEmpty()) {
                    sendCommand("status", lastTarget, lastPwd, silent = true)
                }
            }
        }

        val pauseLine = when {
            pauseWasPaused && currentPauseRemain > 0 -> {
                val m = currentPauseRemain / 60
                val s = currentPauseRemain % 60
                "⏸️ 游戏拦截已暂停 · 剩余 ${m}分${s.toString().padStart(2,'0')}秒 😌"
            }
            !pauseWasPaused && pauseRemainBaseAtMs > 0 && currentPauseRemain <= 0 -> "⏰ 暂停时间到，恢复游戏拦截 🛡️"
            else -> "🎮 游戏正常拦截中 · 守护模式 🛡️"
        }

        val installLine = if (currentBlockInstall) "🚫 禁止安装 APP/游戏" else "📦 允许安装 APP/游戏"

        // 锁屏倒计时（本地算）
        val lockLocalRemain = if (lockWasLocked && !lockWasForever) {
            (lockRemainBaseSec - (now - lockRemainBaseAtMs) / 1000).coerceAtLeast(0)
        } else 0
        // 定时锁本地倒计时到 0 → 自动切"锁屏未开启" + 主动拉一次孩子端确认
        if (lockWasLocked && !lockWasForever && lockLocalRemain <= 0 && lockRemainBaseAtMs > 0) {
            lockWasLocked = false
            if (!lockExpireNotified) {
                lockExpireNotified = true
                if (lastTarget.isNotEmpty()) {
                    sendCommand("status", lastTarget, lastPwd, silent = true)
                }
            }
        }

        val lockLine = when {
            !lockWasLocked -> "🔓 锁屏未开启"
            lockWasForever -> "🔒 永久锁屏开启中"
            else -> {
                val m = lockLocalRemain / 60; val s = lockLocalRemain % 60
                "⏳ 定时锁屏开启中 · 剩余 ${m}分${s.toString().padStart(2,'0')}秒"
            }
        }

        val newText = "$pauseLine\n$installLine\n$lockLine"
        if (newText != lastStatusText) {
            lastStatusText = newText
            binding.tvStatus.text = newText
        }
    }

    /** 执行 hide/show 后重新扫描列表 */
    private fun renderAppListAfterAction() {
        if (lastTarget.isNotEmpty() && lastPwd.isNotEmpty()) {
            sendCommand("list_apps", lastTarget, lastPwd)
        }
    }

    /** 渲染 APP 列表 */
    private fun renderAppList(json: JSONObject) {
        val apps = json.optJSONArray("apps") ?: JSONArray()
        val count = json.optInt("count", apps.length())
        val hiddenCount = json.optInt("hidden_count", 0)

        binding.tvAppListHint.text = "共 $count 个 APP，$hiddenCount 个已隐藏"
        binding.llAppList.removeAllViews()

        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            val pkg = app.getString("package")
            val label = app.getString("label")
            val hidden = app.optBoolean("hidden", false)
            binding.llAppList.addView(createAppRow(pkg, label, hidden))
        }
    }

    /** 创建一个 APP 行（图标+名称+隐藏/显示按钮） */
    private fun createAppRow(pkg: String, label: String, hidden: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 10, 8, 10)
            setBackgroundColor(Color.parseColor(if (hidden) "#FFEBEE" else "#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameTv = TextView(this).apply {
            text = label
            textSize = 15f
            setTypeface(null, if (hidden) Typeface.BOLD_ITALIC else Typeface.NORMAL)
            setTextColor(if (hidden) Color.parseColor("#C62828") else Color.parseColor("#212121"))
        }

        val pkgTv = TextView(this).apply {
            text = if (hidden) "👁‍🗨 已隐藏 · $pkg" else pkg
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
        }

        textCol.addView(nameTv)
        textCol.addView(pkgTv)
        row.addView(textCol)

        val actionBtn = MaterialButton(this).apply {
            text = if (hidden) "显示" else "隐藏"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            if (hidden) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#43A047"))
            } else {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            }
            setOnClickListener {
                val cmd = if (hidden) "show_app?pkg=$pkg" else "hide_app?pkg=$pkg"
                sendCommand(cmd, lastTarget, lastPwd)
            }
        }
        row.addView(actionBtn)

        return row
    }

    /** 渲染孩子APP使用动态（当前正在用 + 最近打开记录） */
    private fun renderAppHistory(json: JSONObject) {
        val sb = StringBuilder()

        val current = json.optJSONObject("current")
        sb.append("📱 当前正在使用：\n")
        if (current != null && current.length() > 0) {
            val label = current.optString("label", current.optString("pkg"))
            val start = current.optLong("start", 0)
            val dur = current.optLong("duration_sec", 0)
            sb.append("▶️ $label\n    ${fmtTime(start)} 打开 · 已用 ${fmtDur(dur)}\n")
        } else {
            sb.append("💤 孩子手机当前未使用 APP（在桌面或息屏）\n")
        }

        sb.append("\n🕒 最近打开记录（新 → 旧）：\n")
        val history = json.optJSONArray("history") ?: JSONArray()
        if (history.length() == 0) {
            sb.append("（暂无记录，孩子端升级到新版后开始记录）\n")
        }
        for (i in 0 until history.length()) {
            val e = history.getJSONObject(i)
            val label = e.optString("label", e.optString("pkg"))
            val start = e.optLong("start", 0)
            val end = e.optLong("end", 0)
            val dur = if (end > 0) ((end - start) / 1000).coerceAtLeast(1)
                      else (System.currentTimeMillis() - start) / 1000
            val range = if (end > 0) "${fmtTime(start)} ~ ${fmtTime(end)}" else "${fmtTime(start)} ~ 现在"
            sb.append("$range  $label（${fmtDur(dur)}）\n")
        }

        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = sb.toString()
            textSize = 14f
            setPadding(48, 32, 48, 32)
        }
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("👀 孩子APP使用动态")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 时间戳 → HH:mm */
    private fun fmtTime(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))

    /** 秒 → 可读时长 */
    private fun fmtDur(sec: Long): String = when {
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec / 3600}小时${(sec % 3600) / 60}分"
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnPause.isEnabled = enabled
        binding.btnResume.isEnabled = enabled
        binding.btnBlockInstall.isEnabled = enabled
        binding.btnAllowInstall.isEnabled = enabled
        binding.btnStatus.isEnabled = enabled
        binding.btnListApps.isEnabled = enabled
        binding.btnAppHistory.isEnabled = enabled
        binding.btnLockNow.isEnabled = enabled
        binding.btnLockFor.isEnabled = enabled
        binding.btnUnlock.isEnabled = enabled
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** DeviceId 输入框锁定/解锁 */
    private fun updateDeviceIdLockState(locked: Boolean) {
        val et = binding.etDeviceId
        val btn = binding.btnUnlockDeviceId
        if (locked) {
            et.isFocusable = false; et.isFocusableInTouchMode = false
            et.isClickable = false; et.isEnabled = false
            et.setBackgroundColor(Color.parseColor("#F5F5F5"))
            btn.text = "🔓 解锁"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#43A047"))
        } else {
            et.isFocusable = true; et.isFocusableInTouchMode = true
            et.isClickable = true; et.isEnabled = true
            et.setBackgroundColor(Color.TRANSPARENT)
            btn.text = "🔒 锁定"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
        }
    }

    /** 密码框锁定/解锁 */
    private fun updatePwdLockState(locked: Boolean) {
        val et = binding.etPwd
        val btn = binding.btnUnlockPwd
        if (locked) {
            et.isFocusable = false; et.isFocusableInTouchMode = false
            et.isClickable = false; et.isEnabled = false
            et.setBackgroundColor(Color.parseColor("#F5F5F5"))
            btn.text = "🔓 解锁"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#43A047"))
        } else {
            et.isFocusable = true; et.isFocusableInTouchMode = true
            et.isClickable = true; et.isEnabled = true
            et.setBackgroundColor(Color.TRANSPARENT)
            btn.text = "🔒 锁定"
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
        }
    }
}
