package com.saihgupr.androidrelay

/**
 * 锁句柄的最小抽象:把 PowerManager.WakeLock / WifiLock 包在接口后面,
 * 真实实现见 [AndroidLockHandles],单测注入 fake 即可验证状态机。
 * 实现必须幂等(重复 acquire/release 安全)且不抛异常。
 */
interface LockHandle {
    /** 获取锁;timeoutMs > 0 表示带超时(超时由系统自动释放),否则一直持有。 */
    fun acquire(timeoutMs: Long)

    /** 释放锁;未持有时为 no-op。 */
    fun release()

    fun isHeld(): Boolean
}

/**
 * 连接期锁策略(v1.2 重写,纯 Kotlin,JVM 可测)。
 *
 * 需求基线:**除用户主动退出(LAN 开关关闭/清理后台)外,连接不允许中断**——
 * 因此只要 LAN 推送启用,WakeLock 与 WifiLock 就全程持有,不再区分播放/暂停/
 * 连接/断开:暂停+息屏期间链路层 ping 必须持续,否则 PC 端 20s 无 PONG 即断链;
 * 断连重连的退避定时器也依赖 CPU 不睡。此前"仅 PLAYING 持锁"的省电策略被有意放弃,
 * 代价是常驻锁带来的少量待机耗电。
 *
 * 超时与续期:WakeLock 带超时 acquire(默认 60s),由持有方(LanPushManager)
 * 每 2s tick 调用 [renew] 重置释放时限——持有方若意外停摆(进程被杀),锁至多
 * 60s 自动失效,不会永久耗电;WifiLock 无超时,随 [releaseAll] 释放。
 *
 * 线程模型:全部幂等、不抛异常;实际只在主线程调用。
 */
class ConnectionLocks(
    private val playbackLock: LockHandle,
    private val wifiLock: LockHandle,
    private val renewalTimeoutMs: Long = RENEWAL_TIMEOUT_MS
) {

    /** 全量获取:WakeLock(带超时)+ WifiLock(无超时);重复调用等效续期。 */
    fun acquire() {
        renew()
        wifiLock.acquire(0L)
    }

    /** 周期续期:重置 WakeLock 释放时限(每 2s tick 调用;WifiLock 无超时不参与)。 */
    fun renew() {
        playbackLock.acquire(renewalTimeoutMs)
    }

    /** 兜底释放(LAN 停用/服务销毁):无条件释放全部锁。 */
    fun releaseAll() {
        playbackLock.release()
        wifiLock.release()
    }

    companion object {
        /** WakeLock 超时:2s tick 续期 + 充分裕量;异常停摆时最迟此时自动释放。 */
        const val RENEWAL_TIMEOUT_MS = 60_000L
    }
}

/**
 * 真实锁的实现工厂:PARTIAL_WAKE_LOCK + WifiLock(API 29+ 用低延迟模式)。
 * 两个锁都关闭引用计数,acquire/release 天然幂等;系统调用全部 try/catch,
 * 个别 ROM 限制时降级为"无锁",绝不影响主流程。
 */
object AndroidLockHandles {

    /** playback 锁:PARTIAL_WAKE_LOCK,CPU 保活仅在 PLAYING 期间由状态机持有。 */
    fun playback(context: android.content.Context): LockHandle {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val lock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "androidrelay:playback")
        lock.setReferenceCounted(false)
        return object : LockHandle {
            override fun acquire(timeoutMs: Long) {
                try {
                    if (lock.isHeld && timeoutMs > 0L) {
                        // 已持有时重复 acquire(timeout) 会重置释放时限(等效续期);
                        // 部分实现先 release 再 acquire,统一走这条路径最稳
                        lock.acquire(timeoutMs)
                    } else if (!lock.isHeld) {
                        if (timeoutMs > 0L) lock.acquire(timeoutMs) else lock.acquire()
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "WakeLock acquire failed: ${e.message}")
                }
            }

            override fun release() {
                try {
                    if (lock.isHeld) lock.release()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "WakeLock release failed: ${e.message}")
                }
            }

            override fun isHeld(): Boolean = try {
                lock.isHeld
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * wifi 锁(组合锁,同时持有两把,WifiLock 本身允许同进程多把并存):
     * - WIFI_MODE_FULL_HIGH_PERF:**唯一在息屏/后台仍生效的模式**(保持射频活跃,
     *   压制省电模式导致的包延迟——服务端 10s ping / 45s 判死依赖它);
     * - WIFI_MODE_FULL_LOW_LATENCY(API 29+):仅前台+亮屏时生效,降低前台推歌延迟;
     *   AOSP 文档明确它"只在亮屏时有效",息屏场景必须由 HIGH_PERF 覆盖。
     * 旧版本(<API 29)只有 HIGH_PERF 一把。全部系统调用 try/catch,个别 ROM
     * 限制时降级为"无锁",绝不影响主流程。
     */
    fun wifi(context: android.content.Context): LockHandle {
        val wm = context.applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val perf = createLock(wm, android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "androidrelay:wifi-perf")
        val lowLatency = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            createLock(wm, android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "androidrelay:wifi-lowlatency")
        } else {
            null
        }
        return object : LockHandle {
            override fun acquire(timeoutMs: Long) {
                acquireOne(perf)
                lowLatency?.let { acquireOne(it) }
            }

            override fun release() {
                releaseOne(perf)
                lowLatency?.let { releaseOne(it) }
            }

            override fun isHeld(): Boolean = try {
                perf.isHeld || (lowLatency?.isHeld ?: false)
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun createLock(
        wm: android.net.wifi.WifiManager,
        mode: Int,
        tag: String
    ): android.net.wifi.WifiManager.WifiLock {
        val lock = wm.createWifiLock(mode, tag)
        lock.setReferenceCounted(false)
        return lock
    }

    private fun acquireOne(lock: android.net.wifi.WifiManager.WifiLock) {
        try {
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "WifiLock acquire failed: ${e.message}")
        }
    }

    private fun releaseOne(lock: android.net.wifi.WifiManager.WifiLock) {
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "WifiLock release failed: ${e.message}")
        }
    }

    private const val TAG = "RelayKeepalive"
}
