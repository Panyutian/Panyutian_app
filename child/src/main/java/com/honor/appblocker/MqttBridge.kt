package com.honor.appblocker

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlin.concurrent.thread
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 孩子端 MQTT 桥接 —— v3.0 跨网络远程控制
 * 使用 HiveMQ MQTT Client (com.hivemq:hivemq-mqtt-client:1.3.10)
 * Async API + .join() 实现同步阻塞
 */
class MqttBridge(private val context: Context) {

    companion object {
        private const val TAG = "MqttBridge-Child"
        private const val STATUS_HEARTBEAT_INTERVAL_MS = 30_000L

        @Volatile var instance: MqttBridge? = null
            private set

        @Volatile var lastError: String = ""
            private set
    }

    private var client: Mqtt3AsyncClient? = null
    @Volatile var connected: Boolean = false
        private set

    private val deviceId = MqttConfig.defaultChildDeviceId(context)
    private var statusThread: Thread? = null
    @Volatile var running: Boolean = false
        private set

    var cmdHandler: ((path: String, params: Map<String, String>) -> String)? = null

    private fun parseBrokerUrl(url: String): Pair<String, Int> {
        val withoutScheme = url.substringAfter("://", url)
        val parts = withoutScheme.split(":")
        val host = parts.firstOrNull() ?: "broker.emqx.io"
        val port = parts.getOrNull(1)?.toIntOrNull()
            ?: if (url.startsWith("ssl://")) 8883 else 1883
        return host to port
    }

    fun start() {
        lastError = "start() 被调用"
        if (running) return
        running = true
        instance = this

        thread(start = true, name = "MqttBridge-Child") {
            runCatching {
                MqttConfig.loadFromOssAsync()
                connectAndSubscribe()
            }.onFailure {
                val fullStack = it.stackTraceToString()
                Log.e(TAG, "MQTT 启动彻底失败", it)
                lastError = "MQTT启动失败[${it.javaClass.simpleName}]: ${it.message}\n$fullStack"
            }
        }
    }

    fun stop() {
        running = false
        if (instance === this) instance = null
        runCatching { client?.disconnect() }
        client = null
        connected = false
        Log.e(TAG, "MqttBridge 已停止")
    }

    private fun connectAndSubscribe() {
        val brokerUrl = MqttConfig.brokerUrl
        val (host, port) = parseBrokerUrl(brokerUrl)
        val clientId = MqttConfig.buildClientId(deviceId, isChild = true)

        // 读取密码用于拼接带哈希的 topic
        val prefs = PrefsManager(context)
        val pwd = prefs.parentPassword

        lastError = "创建客户端 host=$host port=$port"
        Log.e(TAG, "连接 MQTT: broker=$brokerUrl, host=$host, port=$port, clientId=$clientId")

        val willPayload = buildStatusJson(online = false).toString().toByteArray(Charsets.UTF_8)
        val qos = MqttQos.AT_MOST_ONCE  // 🔧 全链路 QoS 0：快速连点不排队，丢了可重发

        val c = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .buildAsync()

        client = c

        lastError = "正在连接..."
        // 带遗嘱消息连接（broker.emqx.io 不需要用户名密码）
        c.connectWith()
            .cleanSession(false)
            .keepAlive(MqttConfig.heartbeatSeconds)
            .willPublish()
                .topic(MqttConfig.topicStatus(deviceId, pwd))
                .payload(willPayload)
                .qos(qos)
                .retain(true)
            .applyWillPublish()
            .send()
            .join()

        connected = true
        lastError = ""
        Log.e(TAG, "MQTT 连接成功！")

        val cmdTopic = MqttConfig.topicCmd(deviceId, pwd)
        lastError = "订阅 $cmdTopic"
        c.subscribeWith()
            .topicFilter(cmdTopic)
            .qos(qos)
            .callback { publish ->
                val payload = publish.getPayloadAsBytes()
                runCatching { handleIncomingCmd(payload) }
                    .onFailure { Log.e(TAG, "处理 CMD 异常: ${it.message}", it) }
            }
            .send()
            .join()
        Log.e(TAG, "已订阅: $cmdTopic")

        publishStatus()
        startStatusHeartbeat()

        // ✅ 连接状态看门狗（HiveMQ 1.3.10 无 listener API）
        startStateWatchdog(cmdTopic, qos, deviceId, pwd)
    }

    /** 看门狗：每5秒检查连接状态，断线重连后自动重订阅 */
    private fun startStateWatchdog(cmdTopic: String, qos: com.hivemq.client.mqtt.datatypes.MqttQos, deviceId: String, pwd: String) {
        thread(start = true, name = "MqttStateWatchdog-Child") {
            while (running) {
                try {
                    val actuallyConnected = client?.state?.isConnected ?: false
                    if (actuallyConnected != connected) {
                        connected = actuallyConnected
                        if (connected) {
                            Log.e(TAG, "✅ MQTT 已重连")
                            // 重连后重新订阅 cmd topic + 推状态
                            Thread.sleep(500)
                            runCatching {
                                client?.subscribeWith()
                                    ?.topicFilter(cmdTopic)
                                    ?.qos(qos)
                                    ?.callback { publish ->
                                        runCatching { handleIncomingCmd(publish.getPayloadAsBytes()) }
                                            .onFailure { Log.e(TAG, "重订阅后处理 CMD 异常: ${it.message}", it) }
                                    }
                                    ?.send()?.join()
                            }.onFailure { Log.e(TAG, "重订阅失败: ${it.message}") }
                            // 推状态（如果有 pending 的立即推；没有也推一次确保 broker 上是最新）
                            pendingStatusPush = false
                            runCatching { publishStatus() }
                        } else {
                            Log.e(TAG, "❌ MQTT 已断线")
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(5000)
            }
        }
    }

    private fun handleIncomingCmd(payload: ByteArray) {
        val jsonStr = String(payload)
        Log.e(TAG, "▶️ 收到 CMD: $jsonStr")

        val json = try { JSONObject(jsonStr) } catch (e: Exception) {
            publishResult("", "?", false, 400, error = "JSON 解析失败")
            return
        }

        val reqId = json.optString("req_id", "")
        val cmd = json.optString("cmd", "")
        val paramsObj = json.optJSONObject("params") ?: JSONObject()

        Log.e(TAG, "▶️ CMD 解析: cmd=$cmd reqId=$reqId")

        // 密码校验已移到 topic 层（topic 里含密码哈希，拼错就收不到）
        val path = "/$cmd"
        val params = mutableMapOf<String, String>()
        paramsObj.keys().forEach { key -> params[key] = paramsObj.optString(key, "") }
        // 密码不出现在 payload 里（topic 哈希认证已足够）

        val handler = cmdHandler
        if (handler == null) {
            Log.e(TAG, "❌ handler 为 null！服务未就绪")
            publishResult(reqId, cmd, false, 500, error = "服务未就绪")
            return
        }

        Log.e(TAG, "▶️ $cmd handler 开始执行...")
        thread(start = true, name = "CMD-$cmd") {
            Log.e(TAG, "▶️ $cmd 线程启动，调用 handler...")
            runCatching { handler(path, params) }
                .onSuccess { httpBody ->
                    Log.e(TAG, "▶️ $cmd handler 返回 OK，解析 HTTP 响应...")
                    val (code, body) = parseHttpResponse(httpBody)
                    Log.e(TAG, "▶️ $cmd HTTP code=$code body=${body.take(100)}")
                    publishResult(reqId, cmd, code in 200..299, code, body = body)
                }.onFailure {
                    Log.e(TAG, "❌ $cmd handler 异常: ${it.message}", it)
                    publishResult(reqId, cmd, false, 500, error = it.message ?: "执行异常")
                }
        }
    }

    private fun parseHttpResponse(fullResponse: String): Pair<Int, String> {
        val headerEnd = fullResponse.indexOf("\r\n\r\n")
        if (headerEnd < 0) return 200 to fullResponse
        val headers = fullResponse.substring(0, headerEnd)
        val body = fullResponse.substring(headerEnd + 4)
        val code = headers.substringAfter("HTTP/1.1 ", "200").substringBefore(' ').toIntOrNull() ?: 200
        return code to body
    }

    private fun publishResult(
        reqId: String, cmd: String, ok: Boolean, httpCode: Int,
        body: String = "", error: String = ""
    ) {
        val prefs = PrefsManager(context)
        val pwd = prefs.parentPassword
        val resultJson = JSONObject().apply {
            put("ver", 1); put("req_id", reqId); put("cmd", cmd)
            put("ok", ok); put("http_code", httpCode)
            if (body.isNotEmpty()) put("body", body)
            if (error.isNotEmpty()) put("error", error)
            put("ts", System.currentTimeMillis())
        }
        Log.e(TAG, "📤 publishResult cmd=$cmd ok=$ok code=$httpCode → result topic (QoS0)")
        runCatching {
            client?.publishWith()
                ?.topic(MqttConfig.topicResult(deviceId, pwd))
                ?.payload(resultJson.toString().toByteArray(Charsets.UTF_8))
                ?.qos(MqttQos.AT_MOST_ONCE)
                ?.retain(false)
                ?.send()
            // 🔧 QoS0 fire-and-forget，不阻塞 CMD 线程
            Log.e(TAG, "📤 publishResult cmd=$cmd ✅ 已提交")
        }.onFailure { Log.e(TAG, "❌ publishResult cmd=$cmd 失败: ${it.message}") }
    }

    private fun startStatusHeartbeat() {
        statusThread?.interrupt()
        statusThread = Thread {
            while (running && !Thread.currentThread().isInterrupted) {
                runCatching { publishStatus() }
                try { Thread.sleep(STATUS_HEARTBEAT_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 供 HTTP 命令处理后立即推送新状态（更新 broker retain） */
    fun immediatePublishStatus() {
        if (connected) {
            // 🔧 publishStatus 现在是 fire-and-forget（无 .join），直接调不阻塞
            runCatching { publishStatus() }
        } else {
            // 如果当前断线，重连成功后看门狗会自动推一次
            pendingStatusPush = true
        }
    }

    @Volatile private var pendingStatusPush = false

    private fun publishStatus() {
        val prefs = PrefsManager(context)
        val pwd = prefs.parentPassword
        val statusBody = JSONObject().apply {
            put("paused", prefs.isPaused())
            put("remain_seconds", prefs.pauseRemainingSeconds())
            put("block_install_apps", prefs.blockInstallApps)
            put("force_locked", prefs.forceLocked)
            put("lock_remaining_seconds", prefs.forceLockRemainingSeconds())
        }
        val statusJson = buildStatusJson(online = true, statusHttpBody = statusBody.toString())
        Log.e(TAG, "📤 publishStatus → status topic (retain=true)")
        runCatching {
            client?.publishWith()
                ?.topic(MqttConfig.topicStatus(deviceId, pwd))
                ?.payload(statusJson.toString().toByteArray(Charsets.UTF_8))
                ?.qos(MqttQos.AT_MOST_ONCE)  // 🔧 STATUS 用 QoS 0：丢了 heartbeat 兜底
                ?.retain(true)
                ?.send()
            // 🔧 关键修复：不 .join() —— fire and forget，避免线程堆积阻塞发送队列
            Log.e(TAG, "📤 publishStatus ✅ 已提交（QoS0 fire-and-forget）")
        }.onFailure { Log.e(TAG, "❌ publishStatus 失败: ${it.message}") }
    }

    private fun buildStatusJson(online: Boolean, statusHttpBody: String = ""): JSONObject {
        val json = JSONObject().apply {
            put("ver", 1); put("online", online); put("ts", System.currentTimeMillis())
            if (online && statusHttpBody.isNotEmpty()) put("status_http_body", statusHttpBody)
            // 去掉 device_id 和 ip — 这些字段有隐私风险，家长端不需要
        }
        val battery = runCatching {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra("level", 0) ?: 0
            val scale = intent?.getIntExtra("scale", 100) ?: 100
            if (scale > 0) level * 100 / scale else 0
        }.getOrDefault(0)
        if (battery > 0) json.put("battery", battery)
        return json
    }

    private fun getLocalIp(): String = runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return@runCatching addr.hostAddress ?: ""
                }
            }
        }
        ""
    }.getOrDefault("")
}
