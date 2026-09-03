package com.honor.appblocker

import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MQTT 连接配置 —— v3.0 跨网络远程控制
 *
 * 加载顺序：
 *  1) 默认配置（EMQX 免费公共 broker，开发期可直接跑通验证）
 *  2) 从阿里云 OSS 动态拉取配置覆盖（OSS 对象需设为公共读）
 *
 * 以后拿到腾讯云 IoT 参数后，只需上传新 JSON 到 OSS，代码零改动。
 */
object MqttConfig {

    private const val TAG = "MqttConfig"

    // ============ 阿里云 OSS 配置（与股票策略共用）============
    private const val OSS_BUCKET = "life-use"
    private const val OSS_REGION = "cn-shanghai"
    private const val OSS_OBJECT = "life_config.json"
    private const val OSS_PUBLIC_URL = "https://$OSS_BUCKET.oss-$OSS_REGION.aliyuncs.com/$OSS_OBJECT"
    private const val OSS_TIMEOUT_MS = 2000

    // ============ 默认 MQTT 配置（EMQX 免费公共 Broker，开发期快速验证）============
    // ⚠️ 上线前务必通过 OSS 覆盖为你自己的 Broker（腾讯云 IoT 或自建 EMQX）
    // 开发期用 tcp://（明文）快速跑通；ssl:// 需要信任自签名证书
    private const val DEFAULT_BROKER_URL = "tcp://broker.emqx.io:1883"
    private const val DEFAULT_USERNAME = ""
    private const val DEFAULT_PASSWORD = ""
    private const val DEFAULT_TOPIC_PREFIX = "honorblocker/v1/"
    private const val DEFAULT_QOS = 1
    private const val DEFAULT_HEARTBEAT_SECONDS = 30
    private const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10

    // ============ 当前生效配置 ============
    @Volatile var brokerUrl: String = DEFAULT_BROKER_URL
    @Volatile var username: String = DEFAULT_USERNAME
    @Volatile var password: String = DEFAULT_PASSWORD
    @Volatile var topicPrefix: String = DEFAULT_TOPIC_PREFIX
    @Volatile var qos: Int = DEFAULT_QOS
    @Volatile var heartbeatSeconds: Int = DEFAULT_HEARTBEAT_SECONDS
    @Volatile var connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS
    @Volatile var loadedFromOss: Boolean = false
    @Volatile var ossLoadError: String = ""

    /**
     * 设备 ID：honor_{Build.SERIAL}（孩子端自动获取）
     * 家长端需手动输入
     *
     * 优先级：Build.SERIAL > ANDROID_ID（更稳定，无权限要求）> 硬编码 fallback
     */
    fun defaultChildDeviceId(context: android.content.Context): String {
        val serial = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        }.getOrNull()

        if (!serial.isNullOrBlank() && serial != "unknown") {
            return "honor_${serial.replace(' ', '_')}"
        }

        // Fallback: ANDROID_ID（不需要任何权限，设备重置才变）
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull()

        if (!androidId.isNullOrBlank()) {
            return "honor_${androidId.take(16)}"  // 取前16位缩短
        }

        return "honor_unknown"
    }

    /** 构建 MQTT ClientId（EMQX/自建 broker 用 {deviceId}，腾讯云 IoT 用 ProductId+DeviceName） */
    fun buildClientId(deviceId: String, isChild: Boolean): String {
        // 如果从 OSS 加载的配置里有 productId，用腾讯云格式；否则用通用格式
        val oss = _productId
        return if (oss.isNotEmpty()) {
            // 腾讯云 IoT: ClientId = {ProductId}{DeviceName}
            oss + deviceId
        } else {
            // EMQX/自建: ClientId = honor_{serial}_child / honor_{serial}_parent
            "${deviceId}_${if (isChild) "child" else "parent"}"
        }
    }

    // 腾讯云 IoT 专用字段（从 OSS JSON 解析）
    @Volatile var _productId: String = ""
    @Volatile var _deviceSecret: String = ""
    @Volatile var _region: String = ""
    @Volatile var _brokerType: String = "emqx"  // "emqx" | "tencent_iot"

    /**
     * 从 OSS 异步拉取配置，成功则覆盖默认值。
     * 在子线程调用，不阻塞主线程。
     */
    fun loadFromOssAsync() {
        thread(start = true, name = "MqttConfig-OSS") {
            runCatching {
                Log.i(TAG, "从 OSS 拉取 MQTT 配置: $OSS_PUBLIC_URL")
                val conn = (URL(OSS_PUBLIC_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = OSS_TIMEOUT_MS
                    readTimeout = OSS_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                val body = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText() ?: ""
                }.getOrDefault("")
                conn.disconnect()

                if (code !in 200..299) {
                    ossLoadError = "OSS HTTP $code"
                    Log.w(TAG, "OSS 拉取失败: HTTP $code, body=${body.take(200)}")
                    return@thread
                }

                val json = JSONObject(body)
                val mqtt = json.optJSONObject("mqtt") ?: run {
                    ossLoadError = "OSS JSON 缺少 mqtt 字段"
                    Log.w(TAG, ossLoadError)
                    return@thread
                }

                // 通用字段
                mqtt.optString("broker_url", "").takeIf { it.isNotEmpty() }?.let { brokerUrl = it }
                mqtt.optString("username", "").let { username = it }
                mqtt.optString("password", "").let { password = it }
                mqtt.optString("topic_prefix", "").takeIf { it.isNotEmpty() }?.let { topicPrefix = it }
                mqtt.optInt("qos", -1).takeIf { it >= 0 }?.let { qos = it }
                mqtt.optInt("heartbeat_seconds", -1).takeIf { it > 0 }?.let { heartbeatSeconds = it }
                mqtt.optInt("connect_timeout", -1).takeIf { it > 0 }?.let { connectTimeoutSeconds = it }
                mqtt.optString("broker_type", "emqx").let { _brokerType = it }

                // 腾讯云 IoT 专用
                mqtt.optString("productId", "").let { _productId = it }
                mqtt.optString("deviceSecret", "").let { _deviceSecret = it }
                mqtt.optString("region", "").let { _region = it }

                // 如果腾讯云 IoT，拼接 broker URL
                if (_brokerType == "tencent_iot" && _productId.isNotEmpty()) {
                    val region = _region.ifEmpty { "ap-guangzhou" }
                    brokerUrl = "tcp://${_productId}.iotcloud.tencentdevices.com:1883"
                    Log.i(TAG, "腾讯云 IoT broker: $brokerUrl")
                }

                loadedFromOss = true
                ossLoadError = ""
                Log.i(TAG, "MQTT 配置加载成功！broker=$brokerUrl, topic=$topicPrefix, brokerType=$_brokerType")
            }.onFailure {
                ossLoadError = it.message ?: "未知错误"
                Log.w(TAG, "OSS 拉取异常: ${it.message}，使用默认配置: $DEFAULT_BROKER_URL")
            }
        }
    }

    /** SHA-256 哈希（用于 topic 里的密码片段，不可逆） */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** HMAC-SHA256 签名（用于 payload 验签，key=密码） */
    fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * 构建 Topic（带密码哈希作为"穷人的 broker 认证"）
     *
     * 完整 topic: honorblocker/v1/{deviceId}/{sha256(pwd).take(16)}/cmd
     * 只有知道密码的家长/孩子才能拼出正确 topic → 才能发命令/订阅
     * 密码本身永远不出现在 payload 里
     */
    fun topicCmd(deviceId: String, pwd: String): String =
        "${topicPrefix}${deviceId}/${sha256(pwd).take(16)}/cmd"
    fun topicResult(deviceId: String, pwd: String): String =
        "${topicPrefix}${deviceId}/${sha256(pwd).take(16)}/result"
    fun topicStatus(deviceId: String, pwd: String): String =
        "${topicPrefix}${deviceId}/${sha256(pwd).take(16)}/status"
    fun topicLog(deviceId: String, pwd: String): String =
        "${topicPrefix}${deviceId}/${sha256(pwd).take(16)}/log"

    /** 调试用：当前配置快照 */
    fun debugSnapshot(): String = buildString {
        append("broker=$brokerUrl\n")
        append("topic=$topicPrefix\n")
        append("brokerType=$_brokerType\n")
        append("productId=$_productId\n")
        append("loadedFromOss=$loadedFromOss\n")
        if (ossLoadError.isNotEmpty()) append("ossError=$ossLoadError\n")
    }
}
