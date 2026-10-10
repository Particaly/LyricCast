package com.saihgupr.androidrelay

import java.security.MessageDigest
import java.util.Locale

/**
 * 播放状态枚举,取值与 LAN 推送协议(docs/protocol.md v1)中的 playback.state 字段一一对应。
 *
 * 注意:MQTT 通道沿用旧的字符串映射(playing/paused/buffering/idle,stopped 与 none 归并为
 * idle),该映射仍留在 MediaSessionListenerService 中,与本枚举互不影响。
 *
 * [fromSessionState] 中引用的 PlaybackState.STATE_* 均为编译期内联的静态常量,
 * 因此本模块可在纯 JVM 单元测试中运行,无需 Robolectric。
 */
enum class PlaybackStatus(val wire: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    STOPPED("stopped"),
    NONE("none");

    companion object {
        /**
         * 把 framework 的 PlaybackState.state 映射为协议状态值。
         *
         * 映射约定:
         * - FAST_FORWARDING / REWINDING 属于正在播放(速度已由 playbackSpeed 体现);
         * - CONNECTING 是瞬态,归入 buffering,避免 UI 误判为暂停;
         * - ERROR 按停止处理;
         * - STATE_NONE / 未知值一律 NONE。
         */
        fun fromSessionState(state: Int): PlaybackStatus = when (state) {
            android.media.session.PlaybackState.STATE_PLAYING -> PLAYING
            android.media.session.PlaybackState.STATE_FAST_FORWARDING -> PLAYING
            android.media.session.PlaybackState.STATE_REWINDING -> PLAYING
            android.media.session.PlaybackState.STATE_PAUSED -> PAUSED
            android.media.session.PlaybackState.STATE_BUFFERING -> BUFFERING
            android.media.session.PlaybackState.STATE_CONNECTING -> BUFFERING
            android.media.session.PlaybackState.STATE_STOPPED -> STOPPED
            android.media.session.PlaybackState.STATE_ERROR -> STOPPED
            else -> NONE
        }
    }
}

/**
 * 一首歌的静态元数据(切歌之前不变)。字段对应协议中的 track 对象。
 *
 * @param id 稳定标识:sha1("title|artist|album|durationMs"),用于歌词缓存键与切歌判定;
 *            artist 为空时以 albumArtist 参与计算,保证尽量非空
 * @param artworkAvailable 设备上报了可用封面(位图或 URI),位图本体在发送时才提取(省电)
 * @param artworkUri 命中 ALBUM_ART_URI / ART_URI / DISPLAY_ICON_URI 时的原始地址,可为 null
 */
data class TrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val genre: String,
    val year: Long,
    val trackNumber: Long,
    val mediaId: String,
    val artworkAvailable: Boolean,
    val artworkUri: String?
) {
    companion object {
        /**
         * 计算 track.id:sha1(title|artist|album|durationMs) 的十六进制小写形式。
         * 输入不区分大小写与首尾空白,避免同一首歌因元数据细微差异生成不同 id。
         */
        fun stableId(title: String, artist: String, album: String, durationMs: Long): String {
            val normalized = "${title.trim().lowercase()}|${artist.trim().lowercase()}|" +
                "${album.trim().lowercase()}|$durationMs"
            val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
        }
    }
}

/**
 * 一次快照时刻的播放动态信息(状态 + 位置锚点)。字段对应协议中的 playback 对象。
 *
 * @param status 协议状态枚举
 * @param positionMs PlaybackState 报告的原始位置(以 [positionUpdateTimeMs] 时刻为锚点);未知时为 -1
 * @param positionKnown false 表示 PLAYBACK_POSITION_UNKNOWN,调用方不得伪造位置
 * @param speed 播放速度(1.0 正常,负值表示倒退,0 表示不动)
 * @param positionUpdateTimeMs 位置锚点对应的 SystemClock.elapsedRealtime() 时刻
 */
data class PlaybackInfo(
    val status: PlaybackStatus,
    val positionMs: Long,
    val positionKnown: Boolean,
    val speed: Float,
    val positionUpdateTimeMs: Long
) {
    /**
     * 估算"当前时刻"的播放位置,供 LAN 推送组装与 PC 端插值对齐。
     *
     * 规则(与 docs/protocol.md 一致):
     * - position 未知时返回 null,调用方必须原样上报 positionKnown=false,不得伪造;
     * - 非 PLAYING 状态直接返回锚点位置(暂停/停止时位置不前进);
     * - PLAYING 时 est = position + (now - updateTime) * speed,并夹到 [0, duration]
     *   (duration <= 0 表示时长未知,只做下界保护)。
     *
     * @param elapsedRealtimeNow 当前 SystemClock.elapsedRealtime() 毫秒值
     * @param durationMs 当前曲目时长毫秒,未知传 0 或负数
     * @return 估算位置毫秒;位置未知时为 null
     */
    fun estimatedPositionMs(elapsedRealtimeNow: Long, durationMs: Long): Long? {
        if (!positionKnown) return null
        val base = positionMs.coerceAtLeast(0L)
        if (status != PlaybackStatus.PLAYING) return base
        val elapsedMs = (elapsedRealtimeNow - positionUpdateTimeMs).coerceAtLeast(0L)
        val estimated = base + (elapsedMs * speed).toLong()
        var result = estimated
        if (result < 0L) result = 0L
        if (durationMs > 0L && result > durationMs) result = durationMs
        return result
    }
}

/**
 * 一次完整的"正在播放"快照:哪个 App 在放什么、放到哪。LAN 推送与日志的最小单元。
 *
 * @param appPackage 来源应用包名
 * @param appName 来源应用显示名(获取失败时与包名相同)
 */
data class MediaInfo(
    val appPackage: String,
    val appName: String,
    val track: TrackInfo,
    val playback: PlaybackInfo
)
