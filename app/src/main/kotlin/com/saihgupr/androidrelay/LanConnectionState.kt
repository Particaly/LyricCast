package com.saihgupr.androidrelay

import java.util.concurrent.CopyOnWriteArrayList

/**
 * LAN 连接状态的进程内广播点:MediaSessionListenerService(实际连接方)写入,
 * MainActivity(状态展示)与 RelayForegroundService(通知)订阅。不落盘、不跨进程。
 *
 * 历史:v1.1 曾有 updatePlayback/touchKeepalive 两组信号,供前台服务的锁状态机
 * 按播放状态持有/续期 WakeLock;v1.2 锁策略改为"LAN 启用期间全程持有"并由连接
 * 持有方 LanPushManager 经 ConnectionLocks 直接管理,这两组信号随之废除。
 *
 * v1.2 新增 [updateTrack]:当前选中曲目的轻量信息(主界面"正在推送"卡展示用),
 * 由 LanPushManager 在发送快照时同步写入,断开/停用/无会话时置 null。
 */
object LanConnectionState {

    /** 主界面展示用的曲目摘要;无选中会话时以 null 表示(见 [updateTrack])。 */
    data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

    private val listeners = CopyOnWriteArrayList<(Boolean, String) -> Unit>()
    private val trackListeners = CopyOnWriteArrayList<(NowPlaying?) -> Unit>()

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var statusText: String = "未启用"

    @Volatile
    private var nowPlaying: NowPlaying? = null

    /** 当前状态快照:(是否已连接, 状态描述文本)。文本供 UI 直接展示,不含敏感信息。 */
    fun snapshot(): Pair<Boolean, String> = connected to statusText

    /** 当前曲目摘要快照;无选中会话/未启用时为 null(主界面"正在推送"卡据此显示空态)。 */
    fun trackSnapshot(): NowPlaying? = nowPlaying

    /**
     * 更新当前曲目摘要并通知监听者;与上次相同时静默(避免 UI 抖动)。
     * 由 LanPushManager 在发送快照的主线程调用;无会话/停用时传 null 清空。
     */
    fun updateTrack(track: NowPlaying?) {
        if (track == nowPlaying) return
        nowPlaying = track
        trackListeners.forEach { it(track) }
    }

    /**
     * 更新状态并通知监听者;内容与上次相同时静默,避免 UI 抖动。
     * 由 LanPushManager 在主线程调用。
     */
    fun update(connected: Boolean, statusText: String) {
        if (connected == this.connected && statusText == this.statusText) return
        this.connected = connected
        this.statusText = statusText
        listeners.forEach { it(connected, statusText) }
    }

    /** 注册监听者(主线程回调);返回值可直接传给 [removeListener]。 */
    fun addListener(listener: (Boolean, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean, String) -> Unit) {
        listeners.remove(listener)
    }

    /** 曲目摘要监听(主界面"正在推送"卡用);回调在写入方线程(主线程),null 表示清空。 */
    fun addTrackListener(listener: (NowPlaying?) -> Unit) {
        trackListeners.add(listener)
    }

    fun removeTrackListener(listener: (NowPlaying?) -> Unit) {
        trackListeners.remove(listener)
    }
}
