package com.saihgupr.androidrelay

import org.json.JSONObject

/**
 * 协议 state/snapshot 消息的组装(纯 Kotlin + org.json,JVM 可测,协议 §2.3/§2.8)。
 *
 * 两类消息载荷同构:type 区分用途("state"=事件/心跳,"snapshot"=连接建立/重发全量);
 * positionMs 一律为发送瞬间估算位置,estimate=null 表示位置未知(positionMs=-1、
 * positionKnown=false,不得伪造)。
 */
object SnapshotJson {

    /**
     * @param type "state" 或 "snapshot"
     * @param seq 连接内单调递增序号
     * @param sentAtMs 发送方时钟 epoch 毫秒
     * @param reason track_change | playback_change | seek | heartbeat | reconnect | request
     * @param artworkHash 当前封面 hash(未缓存/未就绪时为空串)
     */
    fun build(
        type: String,
        seq: Long,
        sentAtMs: Long,
        reason: String,
        info: MediaInfo,
        artworkHash: String
    ): JSONObject = JSONObject().apply {
        put("type", type)
        put("v", 1)
        put("seq", seq)
        put("sentAt", sentAtMs)
        put("reason", reason)
        put(
            "app",
            JSONObject().put("package", info.appPackage).put("name", info.appName)
        )
        put(
            "playback",
            JSONObject().apply {
                put("state", info.playback.status.wire)
                put("positionMs", info.playback.positionMs)
                put("positionKnown", info.playback.positionKnown)
                put("speed", info.playback.speed.toDouble())
            }
        )
        put(
            "track",
            JSONObject().apply {
                put("id", info.track.id)
                put("title", info.track.title)
                put("artist", info.track.artist)
                put("album", info.track.album)
                put("albumArtist", info.track.albumArtist)
                put("durationMs", info.track.durationMs)
                put("genre", info.track.genre)
                put("year", info.track.year)
                put("trackNumber", info.track.trackNumber)
                put("mediaId", info.track.mediaId)
            }
        )
        put(
            "artwork",
            JSONObject().apply {
                put("available", info.track.artworkAvailable)
                put("hash", artworkHash)
            }
        )
    }
}
