package com.saihgupr.androidrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.util.Log

/**
 * LAN 推送的前台保活服务(任务书阶段 2/3)。
 *
 * 职责边界(刻意收窄):
 * - 以 connectedDevice 类型前台化,抬高进程优先级,降低息屏后被系统回收/冻结的概率;
 * - 订阅 [LanConnectionState] 同步通知文案(已连接 / 重连中 / 未配对)。
 *
 * 它**不**持有 LanPushManager 与连接:媒体采集、连接与保活锁(WakeLock/WifiLock,
 * v1.2 起 LAN 启用期间全程持有)都在 MediaSessionListenerService/LanPushManager,
 * 两者同进程,数据流零改动;本服务被杀或启动失败时,连接与锁不受影响,
 * 仅失去系统优先级与常驻通知(启动缺席由 MainActivity.onResume 幂等补拉兜底)。
 *
 * 启动方:MainActivity(配对成功/应用启动/回到前台补拉)与
 * MediaSessionListenerService.onListenerConnected(try/catch,Android 12+ 后台启动
 * 可能抛 ForegroundServiceStartNotAllowedException,失败则降级为仅 Listener 工作)。
 */
class RelayForegroundService : Service() {

    private var connListener: ((Boolean, String) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notify_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Exit requested via notification action")
            // 通知栏「退出」= 完整退出:落退出标记、经监听服务停掉 LAN 通道,
            // 随后自停移除常驻通知;恢复需回应用内点「重新启动推送」或重新保存配置
            AppExit.performExit(this)
            // 先满足 startForegroundService 的 5 秒规则(防御经 startForegroundService 派发的 STOP),再自停
            startInForeground(currentNotification())
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY 被系统重建(进程曾被杀)时不得无视退出标记重新前台化
        if (AppExit.isExited(this)) {
            Log.i(TAG, "Skip restart: user exited")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(currentNotification())
        registerStateListeners()
        Log.i(TAG, "Foreground service started")
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterStateListeners()
        isRunning = false
        Log.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    // ==================== 状态订阅 ====================

    /**
     * 订阅连接状态驱动通知文案;注册前先按当前快照对齐一次,
     * 避免注册前的状态变化丢失。
     *
     * 幂等:onCreate 启动与 onResume 补拉可能在服务首次 onStartCommand 前后各派发一次
     * startForegroundService,重复注册会泄漏监听器(LanConnectionState 为进程级单例)。
     */
    private fun registerStateListeners() {
        if (connListener != null) return
        val conn: (Boolean, String) -> Unit = { _: Boolean, _: String ->
            updateNotification(currentNotification())
        }
        connListener = conn
        LanConnectionState.addListener(conn)
        val (connected, text) = LanConnectionState.snapshot()
        updateNotification(buildNotification(connected, text))
    }

    private fun unregisterStateListeners() {
        connListener?.let { LanConnectionState.removeListener(it) }
        connListener = null
    }

    // ==================== 通知 ====================

    private fun currentNotification(): Notification {
        val (connected, text) = LanConnectionState.snapshot()
        return buildNotification(connected, text)
    }

    /** 组装通知:文案由 [ForegroundStatus] 纯映射;点击回主界面;带「退出」动作(完整退出)。 */
    private fun buildNotification(connected: Boolean, statusText: String): Notification {
        val lanEnabled = LanSettings.isEnabled(this)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RelayForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                ForegroundStatus.label(
                    lanEnabled, connected, statusText, NotificationAccess.granted(this)
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        // minSdk 21:API 29+ 才接受 serviceType 参数;ServiceCompat 内部按版本分发,
        // 低版本忽略类型。FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE 为内联常量,安全引用。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    /** 状态变化时复用同一 ID 更新通知(notify),不重建服务。 */
    private fun updateNotification(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Notify failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RelayKeepalive"

        /**
         * 进程内前台服务存活标记(onCreate/onDestroy 维护,仅服务自身写入):
         * MainActivity.onResume 据此判断是否需要幂等补拉,兜底"后台启动限制/ROM
         * 拉起失败导致服务缺席且无提示"的场景。
         */
        @Volatile
        internal var isRunning = false

        /** 常驻通知渠道 ID;MainActivity 的保活自检靠它判断渠道是否被用户关闭。 */
        const val CHANNEL_ID = "relay_foreground"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.saihgupr.androidrelay.action.STOP_FOREGROUND"

        /** 供各启动方构造统一 intent(显式组件,exported=false 仍可本应用启动)。 */
        fun startIntent(context: Context): Intent = Intent(context, RelayForegroundService::class.java)
    }
}
