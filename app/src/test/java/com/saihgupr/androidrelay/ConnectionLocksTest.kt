package com.saihgupr.androidrelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记录型 fake 锁:验证 ConnectionLocks 发出的 acquire/release 序列。 */
private class FakeLock : LockHandle {
    val events = mutableListOf<String>()
    var held = false
    var lastTimeout = -1L

    override fun acquire(timeoutMs: Long) {
        events += if (timeoutMs > 0) "acquire($timeoutMs)" else "acquire"
        held = true
        lastTimeout = timeoutMs
    }

    override fun release() {
        events += "release"
        held = false
    }

    override fun isHeld(): Boolean = held

    fun acquisitions(): Int = events.count { it.startsWith("acquire") }
}

/**
 * 连接期锁策略(v1.2 重写,回归防护"除主动退出外不断连"的需求基线):
 * - acquire 同时持有 WakeLock(带超时)与 WifiLock(无超时);
 * - renew 仅重置 WakeLock 超时,幂等可重复,绝不存在"暂停/断开即释放"路径;
 * - releaseAll 无条件释放且二次调用安全;全程不区分播放状态。
 */
class ConnectionLocksTest {

    @Test
    fun `acquire holds both locks with timeout on wakelock`() {
        val playback = FakeLock()
        val wifi = FakeLock()
        val locks = ConnectionLocks(playback, wifi, renewalTimeoutMs = 60_000)

        locks.acquire()

        assertTrue(playback.held)
        assertTrue(playback.events.contains("acquire(60000)"))
        assertTrue(wifi.held)
    }

    @Test
    fun `renew resets wakelock timeout only and never releases`() {
        val playback = FakeLock()
        val wifi = FakeLock()
        val locks = ConnectionLocks(playback, wifi, renewalTimeoutMs = 60_000)
        locks.acquire()
        wifi.events.clear()

        locks.renew()
        locks.renew()

        assertTrue(playback.held)
        assertTrue(playback.lastTimeout == 60_000L)
        assertTrue(wifi.events.isEmpty()) // WifiLock 无超时,不参与续期
    }

    @Test
    fun `releaseAll releases both locks and repeated calls are safe`() {
        val playback = FakeLock()
        val wifi = FakeLock()
        val locks = ConnectionLocks(playback, wifi)

        locks.acquire()
        locks.releaseAll()
        assertFalse(playback.held)
        assertFalse(wifi.held)

        locks.releaseAll() // 二次释放不抛错
        locks.renew()      // 释放后续期等效重新获取(服务重启恢复场景)
        assertTrue(playback.held)
    }

    @Test
    fun `locks stay held across renewal cycles regardless of playback state`() {
        // v1.2 语义回归防护:锁不再跟随 PLAYING/暂停/连接状态(旧行为:暂停即释放),
        // 启用即持有,唯一的释放入口是 releaseAll(主动停用/销毁)
        val playback = FakeLock()
        val wifi = FakeLock()
        val locks = ConnectionLocks(playback, wifi)

        locks.acquire()
        repeat(5) { locks.renew() } // 模拟多轮 tick 续期
        assertTrue(playback.held && wifi.held)
    }
}
