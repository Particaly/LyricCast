package com.saihgupr.androidrelay

import java.util.concurrent.CopyOnWriteArrayList

/**
 * LAN 连接状态的进程内广播点:MediaSessionListenerService(实际连接方)写入,
 * MainActivity(状态展示)订阅。不落盘、不跨进程。
 */
object LanConnectionState {

    private val listeners = CopyOnWriteArrayList<(Boolean, String) -> Unit>()

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var statusText: String = "未启用"

    /** 当前状态快照:(是否已连接, 状态描述文本)。文本供 UI 直接展示,不含敏感信息。 */
    fun snapshot(): Pair<Boolean, String> = connected to statusText

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
}
