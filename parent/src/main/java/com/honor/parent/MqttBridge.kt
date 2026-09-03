package com.honor.parent

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlin.concurrent.thread
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 家长端 MQTT 桥接 —— v3.0 跨网络远程控制
 * 使用 HiveMQ MQTT Client (com.hivemq:hivemq-mqtt-client:1.3.10)
 */
class MqttBridge(private val context: Context) {

    companion object {
        private const val TAG = "MqttBridge-Parent"
    }

    private var client: Mqtt3AsyncClient? = null
    @Volatile var connected: Boolean = false
        private set
    @Volatile var running: Boolean = false
        private set

    private val parentDeviceId = "parent_android"
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    // 重连后自动重新订阅的目标（connect state listener 用）
    @Volatile private var lastSubscribedChildId: String = ""
    @Volatile private var lastSubscribedPwd: String = ""

    @Volatile var lastChildStatus: ChildStatus? = null
        private set

    // 🔧 关键：收到 status topic 时通知 UI 更新
    fun interface OnStatusUpdateListener {
        fun onStatusUpdate(statusHttpBody: String)
    }
    @Volatile var statusListener: OnStatusUpdateListener? = null

    data class PendingRequest(
        val cmd: String,
        val reqId: String,
        val callback: (ok: Boolean, httpCode: Int, body: String, error: String) -> Unit
    )

    data class ChildStatus(
        val online: Boolean,
        val ts: Long,
        val statusHttpBody: String,
        val ip: String,
        val battery: Int
    )

    private fun parseBrokerUrl(url: String): Pair<String, Int> {
        val withoutScheme = url.substringAfter("://", url)
        val parts = withoutScheme.split(":")
        val host = parts.firstOrNull() ?: "broker.emqx.io"
        val port = parts.getOrNull(1)?.toIntOrNull()
            ?: if (url.startsWith("ssl://")) 8883 else 1883
        return host to port
    }

    fun start() {
        if (running) return
        running = true
        loadOssConfigAsync()
        thread(start = true, name = "MqttBridge-Parent") {
            runCatching { connect() }
                .onFailure { Log.e(TAG, "MQTT 启动失败: ${it.message}", it) }
        }
    }

    fun stop() {
        running = false
        runCatching { client?.disconnect() }
        client = null
        connected = false
        pendingRequests.clear()
        Log.e(TAG, "家长端 MqttBridge 已停止")
    }

    private fun connect() {
        val brokerUrl = MqttConfig.brokerUrl
        val (host, port) = parseBrokerUrl(brokerUrl)
        val clientId = MqttConfig.buildClientId(parentDeviceId, isChild = false)

        Log.e(TAG, "家长端连接 MQTT: broker=$brokerUrl, host=$host, port=$port, clientId=$clientId")

        val c = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .buildAsync()

        client = c

        runCatching {
            c.connectWith()
                .cleanSession(false)
                .keepAlive(MqttConfig.heartbeatSeconds)
                .send()
                .join()
            connected = true
            Log.e(TAG, "家长端 MQTT 连接成功！")
            // ✅ 启动连接状态看门狗（HiveMQ 1.3.10 没有 listener API，用轮询）
            startStateWatchdog()
        }.onFailure {
            connected = false
            Log.e(TAG, "家长端 MQTT 连接失败: ${it.message}")
            // 10秒后重试
            thread(start = true, name = "reconnect-retry") {
                Thread.sleep(10_000)
                if (!running) return@thread
                Log.e(TAG, "重试连接...")
                runCatching { connect() }
            }
        }
    }

    /** 看门狗：每5秒检查一次连接状态，发现重连后自动重新订阅 */
    private fun startStateWatchdog() {
        thread(start = true, name = "MqttStateWatchdog") {
            while (running) {
                try {
                    val actuallyConnected = client?.state?.isConnected ?: false
                    if (actuallyConnected != connected) {
                        connected = actuallyConnected
                        if (connected) {
                            Log.e(TAG, "✅ MQTT 已重连")
                            // 重连后自动重新订阅孩子端
                            val cid = lastSubscribedChildId
                            val p = lastSubscribedPwd
                            if (cid.isNotEmpty() && p.isNotEmpty()) {
                                Thread.sleep(500)
                                subscribeChildInternal(cid, p)
                            }
                        } else {
                            Log.e(TAG, "❌ MQTT 已断线")
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(5000)
            }
        }
    }

    private fun handleIncoming(topic: String, payload: ByteArray) {
        val jsonStr = String(payload)
        val json = try { JSONObject(jsonStr) } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败: $jsonStr"); return
        }

        when {
            topic.endsWith("/result") -> {
                val reqId = json.optString("req_id", "")
                val cmd = json.optString("cmd", "")
                val ok = json.optBoolean("ok", false)
                val httpCode = json.optInt("http_code", 0)
                val body = json.optString("body", "")
                val error = json.optString("error", "")
                Log.e(TAG, "收到 RESULT: cmd=$cmd ok=$ok code=$httpCode body=${body.take(100)}")

                val pending = pendingRequests.remove(reqId)
                if (pending != null) {
                    try { pending.callback(ok, httpCode, body, error) }
                    catch (e: Exception) { Log.e(TAG, "callback 异常: ${e.message}") }
                }
            }

            topic.endsWith("/status") -> {
                val online = json.optBoolean("online", false)
                val ts = json.optLong("ts", 0)
                val statusHttpBody = json.optString("status_http_body", "")
                val ip = json.optString("ip", "")
                val battery = json.optInt("battery", 0)
                lastChildStatus = ChildStatus(online, ts, statusHttpBody, ip, battery)
                Log.e(TAG, "收到 STATUS: online=$online ts=$ts battery=$battery body=${statusHttpBody.take(100)}")
                // 🔧 关键：通知 MainActivity 刷新 UI
                if (statusHttpBody.isNotEmpty()) {
                    try { statusListener?.onStatusUpdate(statusHttpBody) } catch (_: Exception) {}
                }
            }
        }
    }

    fun sendCmd(
        childDeviceId: String,
        cmd: String,
        params: Map<String, String>,
        pwd: String,
        callback: (ok: Boolean, httpCode: Int, body: String, error: String) -> Unit
    ) {
        if (childDeviceId.isEmpty()) {
            callback(false, 0, "", "请先输入孩子手机的 DeviceId"); return
        }
        if (!connected) {
            callback(false, 0, "", "MQTT 未连接，请检查网络"); return
        }

        val reqId = "rnd_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val cmdJson = JSONObject().apply {
            put("ver", 1); put("req_id", reqId)
            put("cmd", cmd); put("params", JSONObject(params))
            put("ts", System.currentTimeMillis())
        }

        pendingRequests[reqId] = PendingRequest(cmd, reqId, callback)

        // 🔧 QoS 0：跨网络控制命令，丢了可重发，快速连点不排队
        runCatching {
            client?.publishWith()
                ?.topic(MqttConfig.topicCmd(childDeviceId, pwd))
                ?.payload(cmdJson.toString().toByteArray(Charsets.UTF_8))
                ?.qos(MqttQos.AT_MOST_ONCE)
                ?.retain(false)
                ?.send()
            Log.e(TAG, "CMD 已发送: cmd=$cmd reqId=$reqId")
        }.onFailure {
            pendingRequests.remove(reqId)
            callback(false, 0, "", "发布失败: ${it.message}")
        }

        // 超时兜底：list_apps 要遍历几百个APP耗时较长，给 60 秒；其它命令 15 秒
        val timeoutMs = if (cmd == "list_apps") 60_000L else 15_000L
        thread(start = true, name = "CMD-timeout-$reqId") {
            Thread.sleep(timeoutMs)
            val expired = pendingRequests.remove(reqId)
            if (expired != null) {
                Log.e(TAG, "CMD 超时: cmd=$cmd reqId=$reqId")
                try { expired.callback(false, 0, "", "孩子手机超时未响应") } catch (_: Exception) {}
            }
        }
    }

    fun subscribeChild(childDeviceId: String, pwd: String) {
        // 记住：重连后自动重新订阅
        lastSubscribedChildId = childDeviceId
        lastSubscribedPwd = pwd
        subscribeChildInternal(childDeviceId, pwd)
    }

    private fun subscribeChildInternal(childDeviceId: String, pwd: String) {
        val c = client ?: return
        runCatching {
            c.subscribeWith()
                .topicFilter(MqttConfig.topicResult(childDeviceId, pwd))
                .qos(MqttQos.AT_MOST_ONCE)
                .callback { p -> handleIncoming(p.topic.toString(), p.getPayloadAsBytes()) }
                .send().join()
            Log.e(TAG, "已订阅 RESULT: ${MqttConfig.topicResult(childDeviceId, pwd)}")

            c.subscribeWith()
                .topicFilter(MqttConfig.topicStatus(childDeviceId, pwd))
                .qos(MqttQos.AT_MOST_ONCE)
                .callback { p -> handleIncoming(p.topic.toString(), p.getPayloadAsBytes()) }
                .send().join()
            Log.e(TAG, "已订阅 STATUS: ${MqttConfig.topicStatus(childDeviceId, pwd)}")
        }?.onFailure { Log.e(TAG, "订阅失败: ${it.message}") }
    }

    fun unsubscribeChild(childDeviceId: String, pwd: String) {
        runCatching {
            client?.unsubscribeWith()?.topicFilter(MqttConfig.topicResult(childDeviceId, pwd))?.send()?.join()
            client?.unsubscribeWith()?.topicFilter(MqttConfig.topicStatus(childDeviceId, pwd))?.send()?.join()
        }
    }

    private fun loadOssConfigAsync() {
        thread(start = true, name = "MqttConfig-OSS-Parent") {
            runCatching {
                val url = "https://life-use.oss-cn-shanghai.aliyuncs.com/life_config.json"
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000; requestMethod = "GET"
                }
                val code = conn.responseCode
                val body = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText() ?: ""
                }.getOrDefault("")
                conn.disconnect()
                if (code in 200..299) {
                    val mqtt = JSONObject(body).optJSONObject("mqtt") ?: return@runCatching
                    MqttConfig.brokerUrl = mqtt.optString("broker_url", MqttConfig.brokerUrl).ifEmpty { MqttConfig.brokerUrl }
                    MqttConfig.username = mqtt.optString("username", "")
                    MqttConfig.password = mqtt.optString("password", "")
                    mqtt.optString("topic_prefix", "").takeIf { it.isNotEmpty() }?.let { MqttConfig.topicPrefix = it }
                    mqtt.optInt("qos", -1).takeIf { it >= 0 }?.let { MqttConfig.qos = it }
                    mqtt.optInt("heartbeat_seconds", -1).takeIf { it > 0 }?.let { MqttConfig.heartbeatSeconds = it }
                    mqtt.optInt("connect_timeout", -1).takeIf { it > 0 }?.let { MqttConfig.connectTimeoutSeconds = it }
                    MqttConfig.loadedFromOss = true
                    Log.e(TAG, "家长端 OSS 配置加载成功")
                }
            }
        }
    }
}
