package com.saihgupr.androidrelay

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 配对二维码 payload 的解析与构建:
 *
 * ```
 * androidrelay://pair?v=1&port=18765&ip=192.168.1.5&ip=192.168.1.8&token=xxxx
 * ```
 *
 * - PC 插件负责生成(全部局域网 IP 都编入),手机端扫码后按 ip 顺序尝试连接;
 * - 纯 JVM 实现(java.net.URI),不依赖 android.net.Uri,JVM 单测可直接覆盖;
 * - 解析是"严格"的:scheme/host/版本/token/端口/IPv4 任一不合法即返回 null,
 *   扫码页遇到非配对内容时据此提示而不误填配置。
 */
object PairingQr {

    const val SCHEME = "androidrelay"
    const val HOST = "pair"

    /** payload 解析结果;addresses 已验证且去重,按二维码中的顺序(手机逐个尝试)。 */
    data class PairInfo(
        val addresses: List<String>,
        val port: Int,
        val token: String
    ) {
        /** 首选地址:直接填入"PC 地址"输入框。 */
        val primaryHost: String get() = addresses.first()
    }

    /** 与 LanSettings.generateToken 同源的合法字符集(额外允许 - _),16~64 位。 */
    private val TOKEN_REGEX = Regex("[A-Za-z0-9_\\-]{16,64}")
    private val IPV4_REGEX = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    private const val MAX_PAYLOAD_LENGTH = 512
    private const val SUPPORTED_VERSION = 1

    /**
     * 解析扫码内容;非本应用的配对码或任一字段非法时返回 null。
     * 缺省 port 视为 [LanSettings.DEFAULT_PORT],缺省 v 视为 1(向前兼容)。
     */
    fun parse(text: String): PairInfo? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_PAYLOAD_LENGTH) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val rawQuery = uri.rawQuery ?: return null

        val params = parseQuery(rawQuery)
        val version = params.first("v")?.toIntOrNull() ?: SUPPORTED_VERSION
        if (version > SUPPORTED_VERSION) return null

        // port 缺省才取默认值;"有值但畸形"说明 payload 被破坏,从严拒绝
        val portParam = params.first("port")
        val port = if (portParam == null) LanSettings.DEFAULT_PORT
        else portParam.toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        val token = params.first("token") ?: return null
        if (!token.matches(TOKEN_REGEX)) return null

        val addresses = params.all("ip")
            .map { it.trim() }
            .filter { isIpv4(it) }
            .distinct()
        if (addresses.isEmpty()) return null

        return PairInfo(addresses, port, token)
    }

    /**
     * 按协议构建 payload(与插件端 src/qr/pair-qr.ts 保持一致;主要用于测试与
     * 格式对称性验证,手机端日常不生成二维码)。
     */
    fun build(addresses: List<String>, port: Int, token: String): String {
        val parts = mutableListOf("v=$SUPPORTED_VERSION", "port=$port")
        for (address in addresses) {
            if (address.isNotBlank()) parts.add("ip=${urlEncode(address.trim())}")
        }
        parts.add("token=${urlEncode(token)}")
        return "$SCHEME://$HOST?${parts.joinToString("&")}"
    }

    /** 单个 IPv4 且每段 ≤255 才有效(正则只保证格式,范围在这里补)。 */
    private fun isIpv4(text: String): Boolean {
        if (!text.matches(IPV4_REGEX)) return false
        return text.split('.').all { it.toInt() in 0..255 }
    }

    /** 查询串解析为 key → values(支持重复 ip 参数);键值均做 URL 解码。 */
    private fun parseQuery(raw: String): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = decode(if (eq >= 0) pair.substring(0, eq) else pair)
            val value = decode(if (eq >= 0) pair.substring(eq + 1) else "")
            map.getOrPut(key) { mutableListOf() }.add(value)
        }
        return map
    }

    private fun Map<String, List<String>>.first(key: String): String? =
        this[key]?.firstOrNull()

    private fun Map<String, List<String>>.all(key: String): List<String> =
        this[key].orEmpty()

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, StandardCharsets.UTF_8)
    } catch (_: Exception) {
        text
    }

    private fun urlEncode(text: String): String =
        java.net.URLEncoder.encode(text, StandardCharsets.UTF_8)
}
