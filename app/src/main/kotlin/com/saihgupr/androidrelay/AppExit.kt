package com.saihgupr.androidrelay

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 「完整退出」的持久化标记与统一落地动作。
 *
 * 背景:后台由三条互相独立的链路维持 —— 通知使用权的系统绑定
 * ([MediaSessionListenerService])、LAN 前台保活
 * ([RelayForegroundService],START_STICKY)与主界面的幂等补拉
 * (MainActivity.onResume / onListenerConnected)。通知栏的"停止"此前只停
 * 前台服务一条,其余链路会把它重新拉起,用户因此"无法真正退出"。
 *
 * 本对象提供唯一的退出语义:落盘 [KEY_EXITED] 后,所有**自动**启动路径一律
 * 不再拉起服务/建连/持锁;只有界面里的**显式**动作(「重新启动推送」按钮、
 * "保存并重连")负责清除标记并恢复运行。
 *
 * 边界:通知使用权绑定只有用户在系统设置里手动关闭才会解除,应用无法自行解绑;
 * 退出后监听服务仍可能被系统保活,但不再建连、不再持锁、不再前台化,进程静默。
 * 「退出」确认弹窗中已向用户说明这一点。
 */
object AppExit {

    private const val PREFS_FILE = "app_state"
    private const val KEY_EXITED = "user_exited"

    private const val TAG = "AppExit"

    /** 用户是否已请求完整退出;持久化,跨进程重启依然生效。 */
    fun isExited(context: Context): Boolean =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXITED, false)

    fun setExited(context: Context, exited: Boolean) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXITED, exited).apply()
    }

    /**
     * 执行完整退出,通知栏「退出」动作与主界面退出按钮共用此同一路径:
     * 置退出标记 → 通知监听服务停掉 LAN 通道(同进程,startService 立即落地)
     * → 停掉前台保活服务并移除常驻通知。所有步骤幂等,可安全重复调用。
     * 需在允许 startService 的状态下调用(前台界面或前台服务存活期间)。
     */
    fun performExit(context: Context) {
        setExited(context, true)
        try {
            context.startService(
                Intent(context, MediaSessionListenerService::class.java)
                    .setAction(MediaSessionListenerService.ACTION_EXIT)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Notify listener service exit failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        context.stopService(Intent(context, RelayForegroundService::class.java))
    }
}
