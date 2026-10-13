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
     * @param listenerAccessGranted 通知使用权是否已授权;未授权时配置再正确也不会有连接
     *   (监听服务不被系统绑定),须与"重连中"区分,避免用户误以为网络问题
     */
    fun label(
        lanEnabled: Boolean,
        connected: Boolean,
        statusText: String,
        listenerAccessGranted: Boolean = true
    ): String = when {
        !lanEnabled -> "未配对 · LAN 推送未开启"
        statusText.startsWith("认证失败") -> statusText
        // 退出落地瞬间(通知随服务自停移除)可能以"已退出"状态构建一次通知
        statusText.startsWith("已退出") -> "已退出 · 后台运行已停止"
        !listenerAccessGranted -> "待授权 · 需开启通知使用权"
        statusText.startsWith("未启用") -> "已保存 · 等待推送服务启动"
        connected -> if (statusText == "已连接") "已连接 · 正在同步播放信息" else "已连接 · $statusText"
        else -> "重连中 · $statusText"
    }
}
