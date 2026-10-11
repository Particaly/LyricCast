package com.saihgupr.androidrelay

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import org.json.JSONObject

class MediaSessionListenerService : NotificationListenerService(), MediaSessionManager.OnActiveSessionsChangedListener {

    private lateinit var mediaSessionManager: MediaSessionManager
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    private val lastPublishedPayloads = mutableMapOf<String, String>()
    // 每个包名最近一次收到媒体事件的 SystemClock.elapsedRealtime(),用作"最近活跃会话"排序依据
    private val lastEventAt = mutableMapOf<String, Long>()
    // 应用显示名缓存,避免每次回调都查 PackageManager
    private val appLabels = mutableMapOf<String, String>()
    private lateinit var mqttClient: MqttRelay
    // LAN 推送通道(独立于 MQTT 的开关、缓存与连接管理)
    private var lanPushManager: LanPushManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service Created")
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        mqttClient = MqttRelay(this)
        mqttClient.connect()
        lanPushManager = LanPushManager(this).also { it.start() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener Connected")
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
        if (!LanSettings.isEnabled(this)) return
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
                lastPublishedPayloads.remove(pkg)
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
     * 会话被应用主动销毁时的清理:仅注销回调并移除内部状态。
     * MQTT 保持既有行为(销毁不触发发布),避免影响现有 Home Assistant 语义。
     */
    private fun cleanupSession(pkg: String, controller: MediaController) {
        unregisterQuietly(pkg, controller)
        callbacks.remove(pkg)
        controllers.remove(pkg)
        lastPublishedPayloads.remove(pkg)
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

        // ---- 完整媒体信息(LAN 通道数据源;MQTT 行为保持不变)----
        val info = MediaMetadataReader.read(controller, appLabel(pkg))
        if (info != null) {
            Log.d(TAG, "MediaInfo[$pkg]: $info")
            lanPushManager?.onMediaInfo(info, controller)
        }

        // 平台歌曲 ID 诊断(默认关闭;仅本地日志,不推送,见 MediaDiagnostics 注释)
        if (MediaDiagnostics.enabled(applicationContext)) {
            MediaDiagnostics.logSession(controller)
        }

        // ---- 原有 MQTT 上报逻辑(保持原字段与去重行为,勿改)----
        val playbackState = controller.playbackState
        val metadata = controller.metadata

        val stateStr = when (playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            else -> "idle"
        }

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val durationSec = if (durationMs > 0) durationMs / 1000 else 0L
        val app = controller.packageName

        val payload = JSONObject().apply {
            put("state", stateStr)
            put("title", title)
            put("artist", artist)
            put("app", app)
            put("duration", durationSec)
        }.toString()

        // Cache the payload per-app to prevent redundant MQTT publishes on position updates.
        // `onPlaybackStateChanged` fires frequently for progress updates, which
        // causes unnecessary network I/O if the state/title/artist haven't actually changed.
        if (payload == lastPublishedPayloads[app]) {
            return
        }
        lastPublishedPayloads[app] = payload

        Log.d(TAG, "Reporting: $payload")
        mqttClient.publish(null, payload)
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
        mqttClient.disconnect()
    }

    companion object {
        private const val TAG = "RelayService"
    }
}
