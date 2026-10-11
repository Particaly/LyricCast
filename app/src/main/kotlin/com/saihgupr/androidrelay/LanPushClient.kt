package com.saihgupr.androidrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LAN 推送的底层 WebSocket 客户端(手机 → PC,协议见 docs/protocol.md)。
 *
 * 职责仅限:建连、自动重连(指数退避 1s→2s→…封顶 30s)、网络恢复/亮屏解锁立即重试、
 * 收发文本帧。协议语义(hello/state/snapshot/artwork/… )由上层 [LanPushManager] 处理。
 *
 * 链路层存活(协议 §6.1):OkHttp pingInterval=10s 自动发 ws PING 帧,
 * 服务端(ws 库)自动回 PONG;两个周期无 PONG 时 OkHttp 自行判定连接死亡并回调
 * onFailure → 走常规重连。该层不依赖主线程 Handler,paused/stopped 时同样有效。
 *
 * 线程模型:OkHttp 回调在自己的线程;所有对 [Listener] 的回调均切换到主线程,
 * 上层状态因此可以只在主线程维护。
 *
 * @param autoReconnect false 时失败后不重连(设置页"测试连接"用一次性实例)
 */
class LanPushClient(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val listener: Listener,
    private val autoReconnect: Boolean = true
) {

    /** 上层关心的连接事件;均在主线程回调。 */
    interface Listener {
        /** WebSocket 已建立(尚未发送 hello,token 校验未开始)。 */
        fun onConnected()

        /**
         * 连接断开或建立失败。
         * @param reason 人类可读原因(异常消息/关闭码),可展示到设置页
         */
        fun onDisconnected(reason: String)

        /** 收到一条服务端文本消息(已解析为 JSON)。 */
        fun onMessage(message: JSONObject)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // WebSocket 长连接:读超时必须为 0,否则空闲心跳间隔会被误杀
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 链路层保活(协议 §6.1):10s ws PING,两个周期无 PONG 主动判死
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var shutdown = false
    private var reconnectAttempts = 0
    private var reconnectScheduled = false
    private var networkCallbackRegistered = false
    private var screenReceiverRegistered = false

    // 诊断信息(协议 §9 / 任务书阶段 5):断连日志与断线时长统计
    @Volatile private var lastSendOkAt: Long = 0L
    private var downAt: Long = 0L
    private var lastConnectAttemptAt: Long = 0L
    private var lastFailure: String = ""

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // WiFi/数据切换恢复后立即重连,不等退避计时器
            if (!autoReconnect) return
            mainHandler.post {
                if (!shutdown && webSocket == null && !reconnectScheduled) {
                    Log.i(TAG, "Network available, reconnecting immediately")
                    reconnectAttempts = 0
                    doConnect()
                }
            }
        }
    }

    /**
     * 亮屏/解锁广播:息屏期间 ROM 可能静默掐断链路且不触发网络回调,
     * 此时退避计时器最长要等 30s;亮屏瞬间主动重连一次,把恢复时间压到秒级。
     * reconnectNow() 自带 webSocket==null 守卫与 2s 节流,亮屏时连接健在则完全 no-op。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!autoReconnect) return
            Log.i(TAG, "Screen on/user present, requesting immediate reconnect")
            reconnectNow()
        }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            mainHandler.post {
                reconnectAttempts = 0
                if (downAt > 0L) {
                    Log.i(TAG, "Reconnected after ${System.currentTimeMillis() - downAt}ms of outage")
                    downAt = 0L
                }
                listener.onConnected()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.w(TAG, "Non-JSON frame received, ignored")
                return
            }
            mainHandler.post { if (!shutdown) listener.onMessage(message) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 服务端发起关闭:完成握手,避免连接悬挂在半关闭状态
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logFailure("closed", "code=$code reason=$reason")
            handleDown("closed($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logFailure(t.javaClass.simpleName, t.message ?: "failure")
            handleDown(t.message ?: "failure")
        }
    }

    /** 发起(或重新发起)连接;重复调用会先取消旧连接,避免双连接泄漏。 */
    fun connect() {
        shutdown = false
        registerNetworkCallback()
        registerScreenReceiver()
        doConnect()
    }

    /** 停止一切:取消重连定时器、注销网络回调与亮屏广播、断开底层连接并释放线程池。 */
    fun shutdown() {
        shutdown = true
        try {
            if (networkCallbackRegistered) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(networkCallback)
                networkCallbackRegistered = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unregister network callback failed: ${e.message}")
        }
        if (screenReceiverRegistered) {
            try {
                context.unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Unregister screen receiver failed: ${e.message}")
            }
            screenReceiverRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }

    /**
     * 发送一条文本帧(线程安全)。
     * @return true 表示已入队;false 表示当前无连接
     */
    fun send(text: String): Boolean {
        val ok = webSocket?.send(text) ?: false
        if (ok) lastSendOkAt = SystemClock.elapsedRealtime()
        return ok
    }

    /** 当前是否有已建立的 WebSocket。 */
    fun isConnected(): Boolean = webSocket != null

    /**
     * 断连期间来了新事件时由上层调用:取消挂起的退避定时器,立即重连。
     * 节流:距上次建连尝试 < 2s 时忽略(部分 App 每秒回调多次,避免握手风暴);
     * 已有连接在握/已建立时忽略。主线程调用。
     */
    fun reconnectNow() {
        mainHandler.post {
            if (shutdown || webSocket != null || !autoReconnect) return@post
            if (host.isBlank()) return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastConnectAttemptAt < EVENT_RECONNECT_THROTTLE_MS) return@post
            if (reconnectScheduled) {
                mainHandler.removeCallbacks(reconnectRunnable)
                reconnectScheduled = false
            }
            Log.i(TAG, "Event-triggered reconnect")
            reconnectAttempts = 0
            doConnect()
        }
    }

    private fun doConnect() {
        if (shutdown) return
        lastConnectAttemptAt = SystemClock.elapsedRealtime()
        webSocket?.let { old ->
            old.cancel()
            webSocket = null
        }
        if (host.isBlank()) {
            listener.onDisconnected("未配置 PC 地址")
            return
        }
        val request = Request.Builder()
            .url("ws://$host:$port/relay")
            .build()
        webSocket = okHttpClient.newWebSocket(request, wsListener)
    }

    private fun handleDown(reason: String) {
        if (shutdown) return
        webSocket = null
        downAt = System.currentTimeMillis()
        lastFailure = reason
        mainHandler.post {
            if (shutdown) return@post
            listener.onDisconnected(reason)
            if (autoReconnect) scheduleReconnect()
        }
    }

    /**
     * 指数退避:1s、2s、4s… 封顶 30s;成功建连后由 onOpen 重置计数。
     * 使用位移而非乘方,避免重复重连次数过大溢出。
     */
    private fun scheduleReconnect() {
        if (reconnectScheduled || shutdown) return
        reconnectScheduled = true
        val delayMs = minOf(30_000L, 1_000L shl minOf(reconnectAttempts, 5))
        reconnectAttempts++
        Log.d(TAG, "Reconnect in ${delayMs}ms")
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            reconnectScheduled = false
            doConnect()
        }
    }

    /**
     * 断连诊断日志(统一 TAG 便于 `adb logcat -s RelayKeepalive` 过滤):
     * Throwable 类型、关闭码、距上次成功发送的时长、设备空闲(idle)状态、
     * 是否已忽略电池优化。不含 token,不含 host(避免泄露局域网拓扑)。
     */
    private fun logFailure(kind: String, detail: String) {
        val sinceSend = if (lastSendOkAt > 0L) "${SystemClock.elapsedRealtime() - lastSendOkAt}ms" else "never"
        val battery = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            "idle=${pm.isDeviceIdleMode} ignoreBattery=${pm.isIgnoringBatteryOptimizations(context.packageName)}"
        }.getOrDefault("power-info-unavailable")
        Log.w(TAG, "Link down: kind=$kind detail=$detail lastSend=$sinceSend $battery")
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            // 个别 ROM 限制后台注册回调时退化为纯退避重连,不影响功能
            Log.w(TAG, "Register network callback failed: ${e.message}")
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        try {
            // 系统 protected broadcast:NOT_EXPORTED 不影响系统投递,只挡住其他应用
            ContextCompat.registerReceiver(
                context,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Register screen receiver failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RelayKeepalive"
        private const val EVENT_RECONNECT_THROTTLE_MS = 2_000L
    }
}
