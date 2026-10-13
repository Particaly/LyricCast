package com.saihgupr.androidrelay

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * LAN 推送通道的设置读写("lan_config")。
 *
 * 所有方法均为轻量 SharedPreferences 读取;token 懒生成:首次读取时若无则生成
 * 24 位随机串并持久化,用户可在设置页重置。
 */
object LanSettings {

    private const val PREFS_FILE = "lan_config"
    private const val KEY_ENABLED = "lan_enabled"
    private const val KEY_HOST = "lan_host"
    private const val KEY_HOST_ALTERNATES = "lan_host_alternates"
    private const val KEY_PORT = "lan_port"
    private const val KEY_TOKEN = "lan_token"
    private const val KEY_SEND_ARTWORK = "lan_send_artwork"
    private const val KEY_BLACKLIST = "lan_app_blacklist"
    private const val KEY_WHITELIST = "lan_app_whitelist"
    /** 诊断日志存储键;设置页保存时直接写入(读写也可走下方 getter/setter) */
    const val KEY_DIAGNOSTIC_LOG = "lan_diagnostic_log"

    const val DEFAULT_PORT = 18765

    private val tokenRandom = SecureRandom()
    private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val TOKEN_LENGTH = 24

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** LAN 通道总开关;关闭时服务内不创建连接、不上报。 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun host(context: Context): String = prefs(context).getString(KEY_HOST, "") ?: ""

    /**
     * 扫码配对携带的备选 PC 地址(二维码中除首选外的其他网卡 IP,逗号分隔)。
     * 首选连不上时由 [LanPushClient] 轮换尝试;手动修改 PC 地址后会被清空。
     */
    fun hostAlternates(context: Context): List<String> =
        (prefs(context).getString(KEY_HOST_ALTERNATES, "") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 保存扫码配对得到的地址组:首选写入表单键(host),其余进备选
     * (自动去重、剔除与首选相同的项;后续 [com.saihgupr.androidrelay.MainActivity]
     * 的"保存并重连"会再按表单覆盖其他配置)。
     */
    fun savePairingHosts(context: Context, primary: String, candidates: List<String>) {
        val alternates = candidates.map { it.trim() }
            .filter { it.isNotEmpty() && it != primary }
            .distinct()
        prefs(context).edit()
            .putString(KEY_HOST, primary)
            .putString(KEY_HOST_ALTERNATES, alternates.joinToString(","))
            .apply()
    }

    /** 清空备选地址(用户手动修改 PC 地址时调用,避免轮换到失效的旧地址)。 */
    fun clearHostAlternates(context: Context) {
        prefs(context).edit().remove(KEY_HOST_ALTERNATES).apply()
    }

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun sendArtwork(context: Context): Boolean = prefs(context).getBoolean(KEY_SEND_ARTWORK, true)

    /** 不发送 LAN 快照的应用包名集合(如视频类 App),空集合表示不过滤。 */
    fun blacklist(context: Context): Set<String> =
        parsePackages(prefs(context).getString(KEY_BLACKLIST, "") ?: "")

    /**
     * 媒体会话诊断日志开关(任务书阶段 5):开启后每次状态变化打印完整
     * metadata/extras 键值(仅 logcat,不推送),用于实测各音乐 App 的平台歌曲 ID。
     * 默认关闭。
     */
    fun isDiagnosticLoggingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIAGNOSTIC_LOG, false)

    fun setDiagnosticLoggingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DIAGNOSTIC_LOG, enabled).apply()
    }

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
