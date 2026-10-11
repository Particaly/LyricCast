package com.saihgupr.androidrelay

/**
 * 前台服务通知文案的纯映射(LanConnectionState 状态 → 通知正文)。
 * 独立成对象便于 JVM 单测,不依赖 Android 类。
 */
object ForegroundStatus {

    /**
     * @param lanEnabled LAN 推送总开关(未开启时服务通知显示"未配对")
     * @param connected 当前是否有已建立的 WebSocket
     * @param statusText LanConnectionState 的原始状态文本("已连接"/"连接中 …"/"认证失败…"等)
     */
    fun label(lanEnabled: Boolean, connected: Boolean, statusText: String): String = when {
        !lanEnabled -> "未配对:LAN 推送未启用"
        statusText.startsWith("认证失败") -> statusText
        connected -> "已连接 · $statusText"
        else -> "重连中 · $statusText"
    }
}
