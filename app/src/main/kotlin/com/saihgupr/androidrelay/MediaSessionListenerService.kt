package com.saihgupr.androidrelay

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 媒体会话监听 + LAN 推送的宿主服务。
 *
 * 两条实例来源:系统在通知使用权授予后绑定(媒体会话数据的唯一合法来源);
 * MainActivity 在保存/扫码配对后显式 startService 拉起 —— 服务未被系统绑定
 * (或 ROM 忽略 rebind 请求)时,requestRebind 静默无效,显式拉起能让
 * LAN 连接立即建立。两路共用同一实例:onCreate 创建管理器但不启动,
 * 由先到达的 onStartCommand(显式)或 onListenerConnected(系统)启动。
 *
 * 完整退出:用户退出后([AppExit] 标记落盘),系统绑定仍可能把本服务拉起,
 * 但 onCreate/onStartCommand/onListenerConnected 全部按标记关门 —— 不建 LAN、
 * 不拉前台服务,服务只作为被系统绑定的空壳存在;
 * 「重新启动推送」等显式动作清除标记后自然恢复。
 */
class MediaSessionListenerService : NotificationListenerService(), MediaSessionManager.OnActiveSessionsChangedListener {

    private lateinit var mediaSessionManager: MediaSessionManager
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    // 每个包名最近一次收到媒体事件的 SystemClock.elapsedRealtime(),用作"最近活跃会话"排序依据
    private val lastEventAt = mutableMapOf<String, Long>()
    // 应用显示名缓存,避免每次回调都查 PackageManager
    private val appLabels = mutableMapOf<String, String>()
    // LAN 推送通道(开关、缓存与连接管理)
    private var lanPushManager: LanPushManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service Created")
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        // 只创建不启动:启动时点由先到达的 onStartCommand / onListenerConnected 决定,
        // 避免显式拉起路径(两者都会到)重复触发连接重建
        lanPushManager = LanPushManager(this)
    }

    /**
     * 显式 startService 的落地(MainActivity 保存/配对后的双保险之一):
     * 按最新持久化配置启动 LAN 通道(等效"保存并重连",配置变更即时生效),
     * 开关关闭时停通道。系统绑定不会走到这里;重复 start 幂等(旧连接被重建)。
     * 两个分支之外,[ACTION_EXIT] 由 [AppExit.performExit] 派发:停掉两条通道后
     * 状态置"已退出";已处于退出态时收到的任何 start 也一律保持关闭。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT || AppExit.isExited(this)) {
            Log.i(TAG, "Keep channels stopped (exit requested=${intent?.action == ACTION_EXIT})")
            stopChannels()
            return START_NOT_STICKY
        }
        Log.i(TAG, "Service started explicitly (lanEnabled=${LanSettings.isEnabled(this)})")
        if (LanSettings.isEnabled(this)) {
            lanPushManager?.start()
        } else {
            lanPushManager?.stop()
        }
        return START_STICKY
    }

    /**
     * 停掉 LAN 推送通道并把状态置为"已退出":
     * LAN 侧由 [LanPushManager.stop] 断连接、停定时器并释放保活锁。仅退出路径使用。
     */
    private fun stopChannels() {
        lanPushManager?.stop()
        LanConnectionState.update(false, "已退出")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener Connected")
        // 系统绑定 joining 已被显式拉起的实例时不重复 start(通道已在运行);
        // 纯系统绑定路径(未显式 start 过)在此启动,媒体回调随即可用。
        // 已完整退出时不启动(LanPushManager.start 内亦有同款兜底)
        if (!AppExit.isExited(this) && lanPushManager?.isRunning != true) {
            lanPushManager?.start()
        }
        val componentName = ComponentName(this, MediaSessionListenerService::class.java)
        mediaSessionManager.addOnActiveSessionsChangedListener(this, componentName)
        updateActiveSessions(mediaSessionManager.getActiveSessions(componentName))
        tryStartForegroundService()
    }

    /**
     * 尝试拉起前台保活服务(rebind 常发生在后台,Android 12+ 会抛
     * ForegroundServiceStartNotAllowedException、Android 14 缺类型权限抛 SecurityException)。
     * 任何失败都只降级为"仅 Listener 工作",warn 日志,绝不崩溃。
     */
    private fun tryStartForegroundService() {
        if (AppExit.isExited(this) || !LanSettings.isEnabled(this)) return
        try {
            // startForegroundService 允许后台拉起,但 Android 12+ 起受 FGS 启动限制,
            // 失败即降级(onListenerConnected 多数场景发生在前台/解锁后,通常可成功)
            androidx.core.content.ContextCompat.startForegroundService(
                this, RelayForegroundService.startIntent(this)
            )
            Log.i(TAG, "Foreground service start requested")
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start failed (degrading to listener-only): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onActiveSessionsChanged(activeControllers: List<MediaController>?) {
        Log.d(TAG, "Active sessions changed: ${activeControllers?.size}")
        updateActiveSessions(activeControllers)
    }

    // API 24+ 回调:系统可能主动解绑监听(如内存紧张),必须 requestRebind 才能恢复。
    // minSdk 21,需要版本守卫;方法本身在 API < 24 的设备上永远不会被调用。
    override fun onListenerDisconnected() {
        Log.w(TAG, "Listener disconnected, requesting rebind")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, MediaSessionListenerService::class.java))
        }
    }

    private fun updateActiveSessions(activeControllers: List<MediaController>?) {
        val activePackages = activeControllers?.map { it.packageName }?.toSet() ?: emptySet()
        Log.d(TAG, "Active packages: $activePackages")

        // Remove old callbacks for apps that are no longer active
        val iterator = controllers.entries.iterator()
        while (iterator.hasNext()) {
            val (pkg, controller) = iterator.next()
            if (pkg !in activePackages) {
                unregisterQuietly(pkg, controller)
                lastEventAt.remove(pkg)
                iterator.remove()
            }
        }

        activeControllers?.forEach { controller ->
            val pkg = controller.packageName
            if (!controllers.containsKey(pkg)) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        reportState(controller)
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        reportState(controller)
                    }

                    override fun onSessionDestroyed() {
                        Log.d(TAG, "Session destroyed: $pkg")
                        cleanupSession(pkg, controller)
                    }
                }
                controller.registerCallback(callback)
                controllers[pkg] = controller
                callbacks[pkg] = callback

                // Initial report
                reportState(controller)
            }
        }

        logSelectedLanSession()
    }

    /**
     * 会话被应用主动销毁时的清理:仅注销回调并移除内部状态,不触发任何推送。
     */
    private fun cleanupSession(pkg: String, controller: MediaController) {
        unregisterQuietly(pkg, controller)
        callbacks.remove(pkg)
        controllers.remove(pkg)
        lastEventAt.remove(pkg)
        logSelectedLanSession()
    }

    /** 注销回调,吞掉"回调已注销/会话已失效"类异常,保证清理路径不抛错。 */
    private fun unregisterQuietly(pkg: String, controller: MediaController) {
        val callback = callbacks[pkg] ?: return
        try {
            controller.unregisterCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Unregister callback failed for $pkg: ${e.message}")
        }
        callbacks.remove(pkg)
    }

    /**
     * 选择 LAN 通道当前应跟随的会话并输出日志。
     * 阶段 1 仅用于日志观察;阶段 2 的 LanPushManager 复用同一选择逻辑。
     * 过滤列表暂为空(黑/白名单随 LAN 设置一并引入)。
     */
    private fun logSelectedLanSession() {
        val candidates = controllers.map { (pkg, controller) ->
            SessionSelector.Candidate(
                packageName = pkg,
                isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                lastActiveAt = lastEventAt[pkg] ?: 0L
            )
        }
        val selected = SessionSelector.select(candidates)
        Log.d(TAG, "LAN active session: ${selected?.packageName ?: "none"} (candidates=${candidates.map { it.packageName }})")
    }

    private fun reportState(controller: MediaController) {
        val pkg = controller.packageName
        lastEventAt[pkg] = SystemClock.elapsedRealtime()

        val info = MediaMetadataReader.read(controller, appLabel(pkg))
        if (info != null) {
            Log.d(TAG, "MediaInfo[$pkg]: $info")
            lanPushManager?.onMediaInfo(info, controller)
        }

        // 平台歌曲 ID 诊断(默认关闭;仅本地日志,不推送,见 MediaDiagnostics 注释)
        if (MediaDiagnostics.enabled(applicationContext)) {
            MediaDiagnostics.logSession(controller)
        }
    }

    /**
     * 取应用显示名并缓存;查询失败(包被卸载/受限配置文件等)回落为包名本身。
     */
    private fun appLabel(pkg: String): String {
        appLabels[pkg]?.let { return it }
        return try {
            val label = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            appLabels[pkg] = label
            label
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resolve app label for $pkg: ${e.message}")
            pkg
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSessionManager.removeOnActiveSessionsChangedListener(this)
        lanPushManager?.shutdown()
        lanPushManager = null
    }

    companion object {
        private const val TAG = "RelayService"

        /** 完整退出的落地 intent action,由 [AppExit.performExit] 派发。 */
        const val ACTION_EXIT = "com.saihgupr.androidrelay.action.EXIT_CHANNELS"
    }
}
