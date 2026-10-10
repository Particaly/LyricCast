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
 * (docs/protocol.md v1)。底层连接见 [LanPushClient]。
 *
 * 发送策略(协议 §4):
 * - track_change / playback_change / seek 立即发送;playing 时每 8 秒一次心跳快照;
 * - seek 判定 = 当前估算位置与"上次快照按速度外推的位置"偏差 > 2s;
 * - 去重只作用于不含位置变化的重复事件(未命中任何 reason 即静默);
 * - 封面仅在切歌时发送一次(最长边 ≤300px 的 JPEG,Base64),可被 request 重发。
 *
 * 线程模型:本类状态只在主线程读写(服务的媒体回调、Handler tick 均在主线程);
 * 封面解码/压缩在单线程 executor 完成,结果 post 回主线程发送,天然保序。
 * 本类必须由 [MediaSessionListenerService] 持有生命周期:start()/shutdown() 与服务对齐。
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

    // 各会话最新快照/控制器/活跃时刻(主线程访问),用于会话重选
    private val latestInfos = LinkedHashMap<String, MediaInfo>()
    private val latestControllers = HashMap<String, MediaController>()
    private val lastActiveAt = HashMap<String, Long>()
    private var lastSelectedPackage: String? = null

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
            LanConnectionState.update(true, "已连接")
            sendHello()
            // 立即补发完整快照,PC 端(可能是刚启动)无需等待心跳
            resendCurrentSnapshot("heartbeat")
        }

        override fun onDisconnected(reason: String) {
            connected = false
            if (!running) return
            if (authFailed) {
                // token 错误时已停止自动重连,等待用户改配置
                LanConnectionState.update(false, "认证失败:请检查 Token")
            } else {
                LanConnectionState.update(false, "已断开($reason),自动重连中…")
            }
        }

        override fun onMessage(message: JSONObject) {
            handleServerMessage(message)
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 启动管理器:读取设置,若 LAN 开关打开则建立连接并启动心跳/ping 定时器。
     * 与服务 onCreate 对齐;重复调用安全(先 shutdown 旧连接)。
     */
    fun start() {
        running = true
        if (!LanSettings.isEnabled(context)) {
            LanConnectionState.update(false, "未启用")
            return
        }
        connect()
        mainHandler.post(tickRunnable)
    }

    /** 与服务 onDestroy 对齐:停定时器、断连接、释放封面线程池。 */
    fun shutdown() {
        running = false
        connected = false
        mainHandler.removeCallbacksAndMessages(null)
        client?.shutdown()
        client = null
        artworkExecutor.shutdownNow()
        LanConnectionState.update(false, "未启用")
    }

    private fun connect() {
        client?.shutdown()
        val host = LanSettings.host(context)
        val port = LanSettings.port(context)
        client = LanPushClient(context, host, port, clientListener).also { it.connect() }
        LanConnectionState.update(false, "连接中 $host:$port …")
    }

    // ==================== 数据入口 ====================

    /**
     * 服务在每次媒体事件时调用:更新会话状态,重新选择 LAN 应跟随的会话,
     * 并按发送策略决定是否发出快照。
     *
     * 注意:MQTT 通道的上报在服务内独立完成,与本方法互不影响;
     * 本方法早于/晚于 MQTT 去重逻辑都无妨(它没有早退语义)。
     *
     * @param info 本次事件构建的完整媒体信息
     * @param controller 对应的 MediaController,仅切歌时用于提取封面位图
     */
    fun onMediaInfo(info: MediaInfo, controller: MediaController) {
        if (!running || !LanSettings.isEnabled(context)) return
        val pkg = info.appPackage
        lastActiveAt[pkg] = SystemClock.elapsedRealtime()
        latestInfos[pkg] = info
        latestControllers[pkg] = controller

        val selected = SessionSelector.select(
            candidates(),
            blacklist = LanSettings.blacklist(context),
            whitelist = LanSettings.whitelist(context)
        )
        lastSelectedPackage = selected?.packageName
        if (selected == null) {
            sendNoneIfNeeded()
            return
        }
        val selectedInfo = latestInfos[selected.packageName] ?: return
        val selectedController = latestControllers[selected.packageName] ?: return
        processSnapshot(selectedInfo, selectedController)
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
     * positionMs 一律换算为发送时刻的估算位置(协议 §4.3)。
     */
    private fun sendSnapshot(info: MediaInfo, reason: String, estimate: Long?) {
        val message = JSONObject().apply {
            put("type", "state")
            put("v", 1)
            put("seq", ++seq)
            put("sentAt", System.currentTimeMillis())
            put("reason", reason)
            put(
                "app",
                JSONObject().put("package", info.appPackage).put("name", info.appName)
            )
            put(
                "playback",
                JSONObject().apply {
                    put("state", info.playback.status.wire)
                    put("positionMs", estimate ?: -1L)
                    put("positionKnown", info.playback.positionKnown && estimate != null)
                    put("speed", info.playback.speed.toDouble())
                }
            )
            put(
                "track",
                JSONObject().apply {
                    put("id", info.track.id)
                    put("title", info.track.title)
                    put("artist", info.track.artist)
                    put("album", info.track.album)
                    put("albumArtist", info.track.albumArtist)
                    put("durationMs", info.track.durationMs)
                    put("genre", info.track.genre)
                    put("year", info.track.year)
                    put("trackNumber", info.track.trackNumber)
                    put("mediaId", info.track.mediaId)
                }
            )
            put(
                "artwork",
                JSONObject().apply {
                    put("available", info.track.artworkAvailable)
                    put(
                        "hash",
                        if (artworkCache?.trackId == info.track.id) artworkCache!!.hash else ""
                    )
                }
            )
        }
        send(message.toString())

        lastSentTrackId = info.track.id
        lastSentStatus = info.playback.status
        lastSentPositionMs = estimate ?: -1L
        lastSentPositionKnown = info.playback.positionKnown && estimate != null
        lastSentSpeed = info.playback.speed
        lastSentElapsed = SystemClock.elapsedRealtime()
        Log.d(TAG, "Sent state seq=$seq reason=$reason pos=$estimate track=${info.track.title}")
    }

    /** 无活跃会话时:把最后一首歌标记为 state=none 发出(便于 PC 展示"已停止"),只发一次。 */
    private fun sendNoneIfNeeded() {
        if (lastSentStatus == PlaybackStatus.NONE) return
        val info = lastSelectedPackage?.let { latestInfos[it] }
            ?: latestInfos.values.firstOrNull()
            ?: return
        val nonePlayback = info.playback.copy(status = PlaybackStatus.NONE)
        sendSnapshot(info.copy(playback = nonePlayback), "playback_change", null)
    }

    /** 连接建立或收到 request(state) 时补发完整快照。 */
    private fun resendCurrentSnapshot(reason: String) {
        val pkg = lastSelectedPackage ?: return
        val info = latestInfos[pkg] ?: return
        val controller = latestControllers[pkg] ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        sendSnapshot(info, reason, info.playback.estimatedPositionMs(nowElapsed, info.track.durationMs))
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
                "state" -> resendCurrentSnapshot("heartbeat")
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
                    LanConnectionState.update(false, "认证失败:请检查 Token")
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
     * - 每 15 秒一次 ping(测 RTT + NAT 保活);
     * - playing 且距上次快照 ≥8 秒时补发心跳(部分 App 不再回调时兜底)。
     * 断连期间循环保持运转(空转成本可忽略),重连后立即恢复职责。
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
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
                val pkg = lastSelectedPackage
                val info = pkg?.let { latestInfos[it] }
                if (info != null &&
                    info.playback.status == PlaybackStatus.PLAYING &&
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

    private fun candidates(): List<SessionSelector.Candidate> =
        latestInfos.keys.map { pkg ->
            SessionSelector.Candidate(
                packageName = pkg,
                isPlaying = latestInfos[pkg]?.playback?.status == PlaybackStatus.PLAYING,
                lastActiveAt = lastActiveAt[pkg] ?: 0L
            )
        }

    private fun send(text: String): Boolean {
        val ok = client?.send(text) ?: false
        if (!ok) Log.d(TAG, "Send skipped: no connection")
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
