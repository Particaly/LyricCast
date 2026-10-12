package com.saihgupr.androidrelay

import android.content.Context
import android.provider.Settings

/**
 * 通知使用权(通知监听)授权状态的统一判定。
 *
 * [MediaSessionListenerService] 由系统在授权后才绑定,实际连接方 LanPushManager
 * 因此只在授权后才会运行;扫码配对/保存配置只写偏好并发 rebind 请求,授权缺失时
 * 这些操作全部静默无效 —— 界面与通知必须能区分「开关没开」与「被权限卡住」,
 * 不能把后者误报为"同步已关闭"。判定与系统设置页同源:enabled_notification_listeners
 * 是否包含本包名(与 MainActivity 历史行为一致,查询异常按未授权处理)。
 */
object NotificationAccess {

    private const val KEY_ENABLED_LISTENERS = "enabled_notification_listeners"

    /** 本应用是否已获通知使用权。 */
    fun granted(context: Context): Boolean = try {
        Settings.Secure.getString(
            context.contentResolver, KEY_ENABLED_LISTENERS
        )?.contains(context.packageName) == true
    } catch (_: Exception) {
        false
    }
}
