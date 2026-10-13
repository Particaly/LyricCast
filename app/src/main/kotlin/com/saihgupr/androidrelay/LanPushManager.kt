package com.saihgupr.androidrelay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * LAN 推送的策略层:决定"何时发送什么",并把 [MediaInfo] 组装为协议消息
 * (推送协议 v1)。底层连接见 [LanPushClient]。
 *
 * 发送策略(协议 §4):
 * - track_change / playback_change / seek 立即发送;playing 时每 8 秒一次心跳快照;
 * - seek 判定 = 当前估算位置与"上次快照按速度外推的位置"偏差 > 2s;
 * - 去重只作用于不含位置变化的重复事件(未命中任何 reason 即静默);
 * - 封面仅在切歌时发送一次(最长边 ≤300px 的 JPEG,Base64),可被 request 重发。
 *
 * 线程模型:本类状态只在主线程读写(服务的媒体回调、Handler tick 均在主线程);
 * 封面解码/压缩在单线程 executor 完成,结果 post 回主线程发送,天然保序。
 * 本类必须由 [MediaSessionListenerService] 持有生命周期:start()/stop()/shutdown()
 * 与服务的 onCreate、开关切换、onDestroy 对齐。
 *
 * 锁策略(v1.2):WakeLock/WifiLock 由本类经 [ConnectionLocks] 在 LAN 启用期间
 * **全程持有**(启动获取、每 2s tick 续期、shutdown 释放),不再跟随播放状态,
 * 也不再依赖前台服务存活——暂停/息屏/后台期间链路层 ping 与重连定时器因此保持运转,
 * "除主动退出外不断连"由这条链保证。
 */
class LanPushManager(private val context: Context) {

    /** 已发出的封面缓存:供 PC 端 request(artwork) 重发,直到下一首歌覆盖。 */
    private data class ArtworkCache(
        val trackId: String,
        val hash: String,
        val mime: String,
        val dataBase64: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    private var client: LanPushClient? = null
    private var running = false
    private var connected = false
    private var authFailed = false

    // 启用期间全程持有的保活锁(start() 获取、tick 续期、shutdown() 释放;
    // 复用同一实例避免重复 start 时旧锁泄漏)
    private var connectionLocks: ConnectionLocks? = null

    // 各会话最新快照(纯逻辑,断线期间同包覆盖、不累积)与控制器(主线程访问)
    private val sessionCache = SessionStateCache()
    private val latestControllers = HashMap<String, MediaController>()

    // 上次发送的快照要点,用于 seek 外推与切歌判定
    private var lastSentTrackId: String? = null
    private var lastSentStatus: PlaybackStatus? = null
    private var lastSentPositionMs: Long = -1L
    private var lastSentPositionKnown: Boolean = false
    private var lastSentSpeed: Float = 1f
    private var lastSentElapsed: Long = 0L

    private var seq: Long = 0L
    private var lastPingSentAt: Long = 0L
    private var rttMs: Long = -1L
    private var artworkCache: ArtworkCache? = null

    private val clientListener = object : LanPushClient.Listener {
        override fun onConnected() {
            connected = true
            authFailed = false
            // 协议 §2.9:seq 每次连接内从 1 重新计数,snapshot 重置基准
            seq = 0L
            LanConnectionState.update(true, "已连接")
            sendHello()
            // 立即补发全量 snapshot(协议 §2.8),PC 端(可能是刚启动/半开顶替)无需等待心跳
            resendCurrentSnapshot("reconnect")
        }

        override fun onDisconnected(reason: String) {
            connected = false
            if (!running) return
            if (authFailed) {
                // token 错误时已停止自动重连,等待用户改配置
                LanConnectionState.update(false, "认证失败：请检查两端 Token 是否一致")
            } else {
                LanConnectionState.update(false, "已断开($reason)，自动重连中…")
            }
        }

        override fun onMessage(message: JSONObject) {
            handleServerMessage(message)
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 启动管理器:读取设置,若 LAN 开关打开则持有保活锁、建立连接并启动心跳/ping 定时器。
     * 与服务 onCreate/onStartCommand 对齐;重复调用安全(锁复用、旧连接被重建、
     * 旧定时器先摘再挂),每次都会按当前持久化配置重新建连("保存并重连"的落点)。
     * 用户已完整退出([AppExit])时仅对齐状态、绝不建连/拿锁 —— 这是退出语义的
     * 最终防线,即使上游某条启动路径漏了关门也不会破坏"退出不再启动"。
     */
    fun start() {
        running = true
        if (AppExit.isExited(context)) {
            LanConnectionState.updateTrack(null)
            LanConnectionState.update(false, "已退出")
            return
        }
        if (!LanSettings.isEnabled(context)) {
            // 开关刚关闭/服务重启时清掉残留的曲目展示,与"未启用"状态对齐
            LanConnectionState.updateTrack(null)
            LanConnectionState.update(false, "未启用")
            return
        }
        acquireLocks()
        connect()
        // 显式 start 可能重复触发(服务 onCreate 与 onStartCommand 各一次):
        // 先摘掉旧定时器再挂新的,避免出现两条并行的 2s tick 链
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
    }

    /**
     * 停止 LAN 通道(开关关闭时的落点):断连接、停定时器、释放保活锁并清空曲目展示。
     * 与 [shutdown] 的区别:封面线程池保留,通道可再次 [start] 复用。
     */
    fun stop() {
        running = false
        connected = false
        LanConnectionState.updateTrack(null)
        mainHandler.removeCallbacksAndMessages(null)
        client?.shutdown()
        client = null
        connectionLocks?.releaseAll()
        connectionLocks = null
        LanConnectionState.update(false, "未启用")
    }

    /** 与服务 onDestroy 对齐:停通道并释放封面线程池(之后不可再 start)。 */
    fun shutdown() {
        stop()
        artworkExecutor.shutdownNow()
    }

    /** LAN 通道是否在运行(服务决定 onListenerConnected 时是否需要补 start)。 */
    val isRunning: Boolean
        get() = running

    /**
     * 获取保活锁(LAN 启用期全程持有):WakeLock 带 60s 超时靠 tick 续期,
     * WifiLock 无超时;重复 start 复用同一 [ConnectionLocks],只等效续期。
     */
    private fun acquireLocks() {
        val locks = connectionLocks ?: ConnectionLocks(
            playbackLock = AndroidLockHandles.playback(context),
            wifiLock = AndroidLockHandles.wifi(context)
        ).also { connectionLocks = it }
        locks.acquire()
    }

    private fun connect() {
        client?.shutdown()
        val host = LanSettings.host(context)
        val port = LanSettings.port(context)
        // 表单首选 + 扫码带来的备选网卡地址(重连失败时由客户端轮换,见 LanPushClient)
        val hosts = (listOf(host) + LanSettings.hostAlternates(context))
            .filter { it.isNotBlank() }
            .distinct()
        client = LanPushClient(context, hosts, port, clientListener).also { it.connect() }
        // 多候选时先并行竞速(≤1s)再建连,文案据此说明这段额外等待
        LanConnectionState.update(
            false,
            if (hosts.size > 1) "连接中（竞速 ${hosts.size} 个候选地址…）" else "连接中 $host:$port …"
        )
    }

    // ==================== 数据入口 ====================

    /**
     * 服务在每次媒体事件时调用:更新会话状态,重新选择 LAN 应跟随的会话,
     * 并按发送策略决定是否发出快照。
     *
     * @param info 本次事件构建的完整媒体信息
     * @param controller 对应的 MediaController,仅切歌时用于提取封面位图
     */
    fun onMediaInfo(info: MediaInfo, controller: MediaController) {
        if (!running || !LanSettings.isEnabled(context)) return
        sessionCache.update(info, SystemClock.elapsedRealtime())
        latestControllers[info.appPackage] = controller

        val selected = sessionCache.select(
            blacklist = LanSettings.blacklist(context),
            whitelist = LanSettings.whitelist(context)
        ) ?: run {
            sendNoneIfNeeded()
            return
        }
        val selectedController = latestControllers[selected.appPackage] ?: return
        processSnapshot(selected, selectedController)
    }

    // ==================== 发送策略 ====================

    private fun processSnapshot(info: MediaInfo, controller: MediaController) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val estimate = info.playback.estimatedPositionMs(nowElapsed, info.track.durationMs)
        val status = info.playback.status

        val reason = when {
            info.track.id != lastSentTrackId -> "track_change"
            status != lastSentStatus -> "playback_change"
            else -> {
                val projected = projectLastSent(nowElapsed)
                if (estimate != null && projected != null && abs(estimate - projected) > SEEK_THRESHOLD_MS) {
                    "seek"
                } else if (status == PlaybackStatus.PLAYING &&
                    nowElapsed - lastSentElapsed >= HEARTBEAT_INTERVAL_MS
                ) {
                    "heartbeat"
                } else {
                    null // 纯重复事件,静默去重(seek/心跳永远不会走到这里)
                }
            }
        } ?: return

        sendSnapshot(info, reason, estimate)

        if (reason == "track_change" && LanSettings.sendArtwork(context) && info.track.artworkAvailable) {
            scheduleArtwork(info.track.id, controller)
        }
    }

    /**
     * 上次快照外推到 now 的位置;未知位置返回 null(此时不判 seek,
     * 等心跳或状态变化自然校正)。
     */
    private fun projectLastSent(nowElapsed: Long): Long? {
        if (!lastSentPositionKnown || lastSentPositionMs < 0) return null
        if (lastSentStatus != PlaybackStatus.PLAYING) return lastSentPositionMs
        val elapsed = (nowElapsed - lastSentElapsed).coerceAtLeast(0L)
        val projected = lastSentPositionMs + (elapsed * lastSentSpeed).toLong()
        return projected.coerceAtLeast(0L)
    }

    // ==================== 消息组装 ====================

    private fun sendHello() {
        val message = JSONObject().apply {
            put("type", "hello")
            put("v", 1)
            put("token", LanSettings.token(context)) // 仅入网,禁止写日志
            put("deviceId", deviceId())
            put("deviceName", android.os.Build.MODEL ?: "Android")
            put("appVersion", appVersion())
        }
        send(message.toString())
    }

    /**
     * 组装并发送状态快照,同时记录要点供 seek 外推与切歌判定。
     * positionMs 一律换算为发送时刻的估算位置(协议 §2.3)。
     *
     * @param type "state"(事件/心跳)或 "snapshot"(协议 §2.8,连接建立/重发)
     */
    private fun sendSnapshot(info: MediaInfo, reason: String, estimate: Long?, type: String = "state") {
        val known = info.playback.positionKnown && estimate != null
        val message = SnapshotJson.build(
            type = type,
            seq = ++seq,
            sentAtMs = System.currentTimeMillis(),
            reason = reason,
            info = info.copy(
                playback = info.playback.copy(
                    positionMs = estimate ?: -1L,
                    positionKnown = known
                )
            ),
            artworkHash = if (artworkCache?.trackId == info.track.id) artworkCache!!.hash else ""
        )
        send(message.toString())

        lastSentTrackId = info.track.id
        lastSentStatus = info.playback.status
        lastSentPositionMs = estimate ?: -1L
        lastSentPositionKnown = known
        lastSentSpeed = info.playback.speed
        lastSentElapsed = SystemClock.elapsedRealtime()
        // 主界面"正在推送"卡随每次快照同步(含 NONE 空快照,由调用方随后置 null)
        LanConnectionState.updateTrack(
            LanConnectionState.NowPlaying(
                title = info.track.title,
                artist = info.track.artist,
                playing = info.playback.status == PlaybackStatus.PLAYING
            )
        )
        Log.d(TAG, "Sent $type seq=$seq reason=$reason pos=$estimate track=${info.track.title}")
    }

    /** 无活跃会话时:把最后一首歌标记为 state=none 发出(便于 PC 展示"已停止"),只发一次。 */
    private fun sendNoneIfNeeded() {
        if (lastSentStatus == PlaybackStatus.NONE) return
        val info = sessionCache.selectedInfo() ?: sessionCache.anyInfo() ?: return
        val nonePlayback = info.playback.copy(status = PlaybackStatus.NONE)
        sendSnapshot(info.copy(playback = nonePlayback), "playback_change", null)
        // 无选中会话:主界面"正在推送"卡回到空态(PC 端仍保留最后一首的元数据,语义不同)
        LanConnectionState.updateTrack(null)
    }

    /**
     * 连接建立或收到 request(state) 时补发全量快照(协议 §2.8,type=snapshot)。
     * 无活跃会话时发送 state=none 的空快照(沿用最后曲目元数据,PC 显示"无播放");
     * 从未有过任何会话时无内容可发,静默等首个媒体事件。
     */
    private fun resendCurrentSnapshot(reason: String) {
        val info = sessionCache.selectedInfo()
        if (info == null) {
            sendNoneSnapshot(reason)
            return
        }
        val controller = latestControllers[info.appPackage] ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        sendSnapshot(
            info,
            reason,
            info.playback.estimatedPositionMs(nowElapsed, info.track.durationMs),
            type = "snapshot"
        )
    }

    /** 无活跃会话时的空快照;从未有过会话则不发(返回 false)。 */
    private fun sendNoneSnapshot(reason: String): Boolean {
        val info = sessionCache.anyInfo() ?: return false
        sendSnapshot(
            info.copy(playback = info.playback.copy(status = PlaybackStatus.NONE)),
            reason,
            null,
            type = "snapshot"
        )
        LanConnectionState.updateTrack(null)
        return true
    }

    /**
     * 在封面线程提取→缩放→压缩→Base64,完成后回主线程缓存并发送 artwork 消息。
     * 提取顺序:位图键(ALBUM_ART/ART/DISPLAY_ICON),全部未命中再尝试会话上报的 URI
     * (本地 ContentResolver 读取,不消耗手机外网流量)。
     */
    private fun scheduleArtwork(trackId: String, controller: MediaController) {
        artworkExecutor.submit {
            val bytes = try {
                extractArtworkBytes(controller)
            } catch (e: Exception) {
                Log.w(TAG, "Artwork extraction failed: ${e.message}")
                null
            }
            mainHandler.post {
                if (!running || trackId != lastSentTrackId) return@post // 已切歌,丢弃
                if (bytes == null) {
                    Log.d(TAG, "No decodable artwork for $trackId")
                    return@post
                }
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val cache = ArtworkCache(
                    trackId = trackId,
                    hash = sha1Hex(bytes),
                    mime = "image/jpeg",
                    dataBase64 = base64
                )
                artworkCache = cache
                val message = JSONObject().apply {
                    put("type", "artwork")
                    put("v", 1)
                    put("trackId", cache.trackId)
                    put("mime", cache.mime)
                    put("dataBase64", cache.dataBase64)
                }
                send(message.toString())
                Log.d(TAG, "Sent artwork for $trackId (${bytes.size} bytes)")
            }
        }
    }

    private fun extractArtworkBytes(controller: MediaController): ByteArray? {
        val metadata = controller.metadata
        var bitmap: Bitmap? = null
        if (metadata != null) {
            bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        if (bitmap == null && metadata != null) {
            val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            if (!uri.isNullOrBlank()) {
                try {
                    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
                        bitmap = BitmapFactory.decodeStream(input)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Artwork URI decode failed: ${e.message}")
                }
            }
        }
        val source = bitmap ?: return null
        val longest = maxOf(source.width, source.height)
        val scaled = if (longest > ARTWORK_MAX_SIDE) {
            val ratio = ARTWORK_MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, output)
        val bytes = output.toByteArray()
        if (bytes.size > MAX_ARTWORK_BYTES) {
            Log.w(TAG, "Artwork too large after compress (${bytes.size} bytes), dropped")
            return null
        }
        return bytes
    }

    // ==================== 服务端消息 ====================

    private fun handleServerMessage(message: JSONObject) {
        when (message.optString("type")) {
            "ping" -> {
                // 协议要求:收到 ping 必须尽快回 pong
                val pong = JSONObject().apply {
                    put("type", "pong")
                    put("v", 1)
                    put("t", message.optLong("t"))
                    put("serverTime", System.currentTimeMillis())
                }
                send(pong.toString())
            }
            "pong" -> {
                val rtt = System.currentTimeMillis() - message.optLong("t")
                if (rtt in 0..60_000) rttMs = rtt
            }
            "request" -> when (message.optString("what")) {
                "artwork" -> {
                    val cache = artworkCache
                    if (cache != null) {
                        val artwork = JSONObject().apply {
                            put("type", "artwork")
                            put("v", 1)
                            put("trackId", cache.trackId)
                            put("mime", cache.mime)
                            put("dataBase64", cache.dataBase64)
                        }
                        send(artwork.toString())
                    }
                }
                "state" -> resendCurrentSnapshot("request") // 协议 §2.8:snapshot 应答
            }
            "hello_ack" -> Unit // 已连接状态由 onConnected 维护
            "error" -> {
                val code = message.optString("code")
                if (code == "bad_token") {
                    authFailed = true
                    client?.let { c ->
                        c.shutdown()
                        client = null
                    }
                    connected = false
                    LanConnectionState.update(false, "认证失败：请检查两端 Token 是否一致")
                } else {
                    Log.w(TAG, "Server error: $code ${message.optString("message")}")
                }
            }
            else -> Unit // 未知类型忽略(向前兼容)
        }
    }

    // ==================== 定时器 ====================

    /**
     * 2 秒一跳的轻量定时器:
     * - 每跳续期保活锁(LAN 启用期间 WakeLock/WifiLock 全程持有,v1.2);
     * - 每 15 秒一次 ping(测 RTT + NAT 保活,应用层,协议 §2.6);
     * - playing 且距上次快照 ≥8 秒时补发心跳(部分 App 不再回调时兜底)。
     * 断连期间循环保持运转(重连退避与锁续期都依赖它),重连后立即恢复职责。
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            connectionLocks?.renew()
            if (connected) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPingSentAt >= PING_INTERVAL_MS) {
                    lastPingSentAt = now
                    val ping = JSONObject().apply {
                        put("type", "ping")
                        put("v", 1)
                        put("t", System.currentTimeMillis())
                    }
                    send(ping.toString())
                }
                val info = sessionCache.selectedInfo()
                if (info?.playback?.status == PlaybackStatus.PLAYING &&
                    now - lastSentElapsed >= HEARTBEAT_INTERVAL_MS
                ) {
                    val estimate = info.playback.estimatedPositionMs(now, info.track.durationMs)
                    sendSnapshot(info, "heartbeat", estimate)
                }
            }
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // ==================== 工具 ====================

    private fun send(text: String): Boolean {
        val ok = client?.send(text) ?: false
        if (!ok) {
            Log.d(TAG, "Send skipped: no connection")
            // 断连期间来了新事件:取消退避等待,立即重连(重连后由 onConnected 补 snapshot)
            client?.reconnectNow()
        }
        return ok
    }

    /** 稳定设备标识:由硬件指纹哈希而来,不含 IMEI 等个人标识信息。 */
    private fun deviceId(): String =
        sha1Hex((android.os.Build.FINGERPRINT ?: "unknown").toByteArray(Charsets.UTF_8)).take(16)

    /** 从 PackageManager 读取版本名(AGP 8 默认关闭 BuildConfig,走系统接口最稳)。 */
    private fun appVersion(): String = try {
        val pm = context.packageManager
        pm.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { String.format("%02x", it) }

    companion object {
        private const val TAG = "LanPush"
        private const val HEARTBEAT_INTERVAL_MS = 8_000L
        private const val SEEK_THRESHOLD_MS = 2_000L
        private const val PING_INTERVAL_MS = 15_000L
        private const val TICK_INTERVAL_MS = 2_000L
        private const val ARTWORK_MAX_SIDE = 300
        private const val ARTWORK_JPEG_QUALITY = 80
        private const val MAX_ARTWORK_BYTES = 1_500_000
    }
}
