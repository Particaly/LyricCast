package com.saihgupr.androidrelay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * 职责仅限:建连、自动重连(指数退避 1s→2s→…封顶 30s)、网络恢复立即重试、
 * 收发文本帧。协议语义(hello/state/artwork/… )由上层 [LanPushManager] 处理。
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
        .build()

    private var webSocket: WebSocket? = null
    private var shutdown = false
    private var reconnectAttempts = 0
    private var reconnectScheduled = false
    private var networkCallbackRegistered = false

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

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            mainHandler.post {
                reconnectAttempts = 0
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
            handleDown("closed($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDown(t.message ?: "failure")
        }
    }

    /** 发起(或重新发起)连接;重复调用会先取消旧连接,避免双连接泄漏。 */
    fun connect() {
        shutdown = false
        registerNetworkCallback()
        doConnect()
    }

    /** 停止一切:取消重连定时器、注销网络回调、断开底层连接并释放线程池。 */
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
    fun send(text: String): Boolean = webSocket?.send(text) ?: false

    /** 当前是否有已建立的 WebSocket。 */
    fun isConnected(): Boolean = webSocket != null

    private fun doConnect() {
        if (shutdown) return
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
        mainHandler.postDelayed({
            reconnectScheduled = false
            doConnect()
        }, delayMs)
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

    companion object {
        private const val TAG = "LanPush"
    }
}
