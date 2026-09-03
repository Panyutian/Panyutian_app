package com.honor.parent

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
 * MQTT 连接配置 —— 家长端（与孩子端共用同一逻辑）
 *
 * 加载顺序：
 *  1) 默认配置（EMQX 免费公共 broker，开发期可直接跑通验证）
 *  2) 从阿里云 OSS 动态拉取配置覆盖
 */
object MqttConfig {

    private const val TAG = "MqttConfig"

    // ============ 阿里云 OSS 配置 ============
    private const val OSS_BUCKET = "life-use"
    private const val OSS_REGION = "cn-shanghai"
    private const val OSS_OBJECT = "life_config.json"
    private const val OSS_PUBLIC_URL = "https://$OSS_BUCKET.oss-$OSS_REGION.aliyuncs.com/$OSS_OBJECT"
    private const val OSS_TIMEOUT_MS = 2000

    // ============ 默认 MQTT 配置（EMQX 免费公共 Broker）============
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

    // 腾讯云 IoT 专用
    @Volatile var _productId: String = ""
    @Volatile var _deviceSecret: String = ""
    @Volatile var _region: String = ""
    @Volatile var _brokerType: String = "emqx"

    /** 构建 MQTT ClientId */
    fun buildClientId(deviceId: String, isChild: Boolean): String {
        val oss = _productId
        return if (oss.isNotEmpty()) {
            oss + deviceId
        } else {
            "${deviceId}_${if (isChild) "child" else "parent"}"
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

    /** 构建 Topic（带密码哈希作为"穷人的 broker 认证"） */
    fun topicCmd(deviceId: String, pwd: String): String =
        "${topicPrefix}${deviceId}/${sha256(pwd).take(16)}/cmd"
    fun topicResult(deviceId: String, pwd: String): String =
        "${topicPrefix}${deviceId}/${sha256(pwd).take(16)}/result"
    fun topicStatus(deviceId: String, pwd: String): String =
        "${topicPrefix}${deviceId}/${sha256(pwd).take(16)}/status"

    fun debugSnapshot(): String = buildString {
        append("broker=$brokerUrl\n")
        append("topic=$topicPrefix\n")
        append("brokerType=$_brokerType\n")
        append("productId=$_productId\n")
        append("loadedFromOss=$loadedFromOss\n")
        if (ossLoadError.isNotEmpty()) append("ossError=$ossLoadError\n")
    }
}
