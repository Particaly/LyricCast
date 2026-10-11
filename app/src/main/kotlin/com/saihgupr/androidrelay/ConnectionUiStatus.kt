package com.saihgupr.androidrelay

/**
 * 主界面连接状态的纯映射(LanConnectionState 状态 → UI 展示形态)。
 * 与 [ForegroundStatus] 同思路:独立成纯 Kotlin 便于 JVM 单测,不依赖 Android 类。
 *
 * 颜色归属由 MainActivity 决定(本类型不引用 R 类),约定:
 * CONNECTED → 绿;CONNECTING / RECONNECTING → 橙;AUTH_FAILED → 红;DISABLED → 灰。
 */
enum class ConnectionUiStatus(val shortLabel: String) {
    CONNECTED("已连接"),
    CONNECTING("连接中"),
    RECONNECTING("重连中"),
    AUTH_FAILED("认证失败"),
    DISABLED("未启用")
}

object ConnectionUi {

    /**
     * 把 LanConnectionState 的 (是否已连接, 状态文本) 归类为 UI 状态。
     *
     * 判定依赖 LanPushManager 写入的固定文案约定("未启用"/"连接中 …"/"已断开(…)自动重连中…"
     * /"认证失败…",见 LanPushManager 各 update 调用点),与 ForegroundStatus 的
     * startsWith 匹配方式保持一致;未识别文本且未连接时兜底为 RECONNECTING。
     *
     * @param connected 当前是否有已建立的 WebSocket
     * @param statusText LanConnectionState 的原始状态文本
     */
    fun from(connected: Boolean, statusText: String): ConnectionUiStatus = when {
        statusText.startsWith("认证失败") -> ConnectionUiStatus.AUTH_FAILED
        statusText.startsWith("未启用") -> ConnectionUiStatus.DISABLED
        connected -> ConnectionUiStatus.CONNECTED
        statusText.startsWith("连接中") -> ConnectionUiStatus.CONNECTING
        else -> ConnectionUiStatus.RECONNECTING
    }
}
