package com.saihgupr.androidrelay

/**
 * 主界面连接状态的纯映射(LanConnectionState 状态 → UI 展示形态)。
 * 与 [ForegroundStatus] 同思路:独立成纯 Kotlin 便于 JVM 单测,不依赖 Android 类。
 *
 * 颜色归属由 MainActivity 决定(本类型不引用 R 类),约定:
 * CONNECTED → 绿;CONNECTING / RECONNECTING / NEEDS_ACCESS → 橙;AUTH_FAILED → 红;
 * DISABLED → 灰。
 */
enum class ConnectionUiStatus(val shortLabel: String) {
    CONNECTED("已连接"),
    CONNECTING("连接中"),
    RECONNECTING("重连中"),
    AUTH_FAILED("认证失败"),

    /** LAN 未启用(开关关闭/服务未随新配置启动);具体文案由界面按是否已配置分化。 */
    DISABLED("未启用"),

    /**
     * 用户已执行「完整退出」([AppExit] 标记落盘):所有通道已停、不再自动重启。
     * 与 DISABLED 的区别在于恢复动作不同 —— 后者去开开关,前者点「重新启动推送」。
     */
    EXITED("已退出"),

    /**
     * 通知使用权未授权:监听服务不会被系统绑定,授权前连接无从建立。
     * 与 DISABLED 的区别在于修复动作不同 —— 前者去开开关,后者去授权。
     */
    NEEDS_ACCESS("需通知使用权")
}

object ConnectionUi {

    /**
     * 把 LanConnectionState 的 (是否已连接, 状态文本) 归类为 UI 状态。
     *
     * 判定依赖 LanPushManager 写入的固定文案约定("未启用"/"连接中 …"/"已断开(…)自动重连中…"
     * /"认证失败…",见 LanPushManager 各 update 调用点),与 ForegroundStatus 的
     * startsWith 匹配方式保持一致;未识别文本且未连接时兜底为 RECONNECTING。
     *
     * @param listenerAccessGranted 通知使用权是否已授权;文本为"未启用"且未授权时
     *   归为 [ConnectionUiStatus.NEEDS_ACCESS](授权前服务不会启动,重试/等待无意义)
     */
    fun from(
        connected: Boolean,
        statusText: String,
        listenerAccessGranted: Boolean = true
    ): ConnectionUiStatus = when {
        statusText.startsWith("认证失败") -> ConnectionUiStatus.AUTH_FAILED
        statusText.startsWith("已退出") -> ConnectionUiStatus.EXITED
        statusText.startsWith("未启用") && !listenerAccessGranted -> ConnectionUiStatus.NEEDS_ACCESS
        statusText.startsWith("未启用") -> ConnectionUiStatus.DISABLED
        connected -> ConnectionUiStatus.CONNECTED
        statusText.startsWith("连接中") -> ConnectionUiStatus.CONNECTING
        else -> ConnectionUiStatus.RECONNECTING
    }
}
