package com.saihgupr.androidrelay

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * LAN 推送通道的设置读写(独立于 MQTT 的 "mqtt_config",两条通道互不影响)。
 *
 * 所有方法均为轻量 SharedPreferences 读取;token 懒生成:首次读取时若无则生成
 * 24 位随机串并持久化,用户可在设置页重置。
 */
object LanSettings {

    private const val PREFS_FILE = "lan_config"
    private const val KEY_ENABLED = "lan_enabled"
    private const val KEY_HOST = "lan_host"
    private const val KEY_PORT = "lan_port"
    private const val KEY_TOKEN = "lan_token"
    private const val KEY_SEND_ARTWORK = "lan_send_artwork"
    private const val KEY_BLACKLIST = "lan_app_blacklist"
    private const val KEY_WHITELIST = "lan_app_whitelist"

    const val DEFAULT_PORT = 18765

    private val tokenRandom = SecureRandom()
    private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val TOKEN_LENGTH = 24

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** LAN 通道总开关;关闭时服务内不创建连接、不上报。 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun host(context: Context): String = prefs(context).getString(KEY_HOST, "") ?: ""

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun sendArtwork(context: Context): Boolean = prefs(context).getBoolean(KEY_SEND_ARTWORK, true)

    /** 不发送 LAN 快照的应用包名集合(如视频类 App),空集合表示不过滤。 */
    fun blacklist(context: Context): Set<String> =
        parsePackages(prefs(context).getString(KEY_BLACKLIST, "") ?: "")

    /** 仅发送白名单内应用的快照;非空时优先于黑名单生效。 */
    fun whitelist(context: Context): Set<String> =
        parsePackages(prefs(context).getString(KEY_WHITELIST, "") ?: "")

    /**
     * 读取配对 Token,不存在时生成并持久化(≥16 位随机字符串,协议要求)。
     * 该值不得出现在任何日志中。
     */
    @Synchronized
    fun token(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = generateToken()
        p.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    /** 生成新的随机 Token(24 位字母数字),由设置页"重新生成"按钮调用并持久化。 */
    fun generateToken(): String {
        val sb = StringBuilder(TOKEN_LENGTH)
        repeat(TOKEN_LENGTH) { sb.append(TOKEN_ALPHABET[tokenRandom.nextInt(TOKEN_ALPHABET.length)]) }
        return sb.toString()
    }

    /**
     * 解析逗号/分号/空白分隔的包名列表为集合,忽略空项与大小写差异
     * (匹配时也忽略大小写,见 [SessionSelector.passesFilter])。
     */
    fun parsePackages(raw: String): Set<String> =
        raw.split(',', ';', '\n', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
}
