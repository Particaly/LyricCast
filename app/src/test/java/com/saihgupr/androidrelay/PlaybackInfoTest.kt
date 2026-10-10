package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置估算纯逻辑测试(docs/protocol.md 的 Android 端约定)。
 * PlaybackInfo 不依赖 Android 运行时,elapsedRealtime 由测试显式传入。
 */
class PlaybackInfoTest {

    private fun playing(positionMs: Long, speed: Float, updatedAt: Long) = PlaybackInfo(
        status = PlaybackStatus.PLAYING,
        positionMs = positionMs,
        positionKnown = positionMs != -1L,
        speed = speed,
        positionUpdateTimeMs = updatedAt
    )

    @Test
    fun `playing advances by elapsed times speed`() {
        val info = playing(positionMs = 10_000, speed = 1.0f, updatedAt = 1_000)
        assertEquals(16_000L, info.estimatedPositionMs(elapsedRealtimeNow = 7_000, durationMs = 0))
    }

    @Test
    fun `playing with higher speed advances faster`() {
        val info = playing(positionMs = 10_000, speed = 1.5f, updatedAt = 1_000)
        assertEquals(16_000L, info.estimatedPositionMs(elapsedRealtimeNow = 5_000, durationMs = 0))
    }

    @Test
    fun `paused stays at anchor position`() {
        val info = PlaybackInfo(
            status = PlaybackStatus.PAUSED,
            positionMs = 30_000,
            positionKnown = true,
            speed = 1.0f,
            positionUpdateTimeMs = 1_000
        )
        assertEquals(30_000L, info.estimatedPositionMs(elapsedRealtimeNow = 999_999, durationMs = 200_000))
    }

    @Test
    fun `unknown position yields null and must not be faked`() {
        val info = playing(positionMs = -1L, speed = 1.0f, updatedAt = 1_000)
        assertNull(info.estimatedPositionMs(elapsedRealtimeNow = 5_000, durationMs = 100_000))
        assertFalse(info.positionKnown)
    }

    @Test
    fun `estimate is clamped to zero and duration while playing`() {
        val overshoot = playing(positionMs = 95_000, speed = 1.0f, updatedAt = 0)
        assertEquals(100_000L, overshoot.estimatedPositionMs(elapsedRealtimeNow = 20_000, durationMs = 100_000))

        val rewinding = playing(positionMs = 2_000, speed = -2.0f, updatedAt = 0)
        assertEquals(0L, rewinding.estimatedPositionMs(elapsedRealtimeNow = 10_000, durationMs = 100_000))
    }

    @Test
    fun `zero speed keeps position even while playing`() {
        val info = playing(positionMs = 12_345, speed = 0f, updatedAt = 0)
        assertEquals(12_345L, info.estimatedPositionMs(elapsedRealtimeNow = 50_000, durationMs = 100_000))
    }

    @Test
    fun `stale update timestamp does not move position backwards`() {
        // now 早于锚点(理论上不该发生,防御 elapsedRealtime 回绕/异常),取 elapsed=0
        val info = playing(positionMs = 42_000, speed = 1.0f, updatedAt = 10_000)
        assertEquals(42_000L, info.estimatedPositionMs(elapsedRealtimeNow = 5_000, durationMs = 100_000))
    }

    @Test
    fun `session state mapping covers protocol values`() {
        assertEquals(PlaybackStatus.PLAYING, PlaybackStatus.fromSessionState(android.media.session.PlaybackState.STATE_PLAYING))
        assertEquals(PlaybackStatus.PLAYING, PlaybackStatus.fromSessionState(android.media.session.PlaybackState.STATE_FAST_FORWARDING))
        assertEquals(PlaybackStatus.PAUSED, PlaybackStatus.fromSessionState(android.media.session.PlaybackState.STATE_PAUSED))
        assertEquals(PlaybackStatus.BUFFERING, PlaybackStatus.fromSessionState(android.media.session.PlaybackState.STATE_BUFFERING))
        assertEquals(PlaybackStatus.STOPPED, PlaybackStatus.fromSessionState(android.media.session.PlaybackState.STATE_STOPPED))
        assertEquals(PlaybackStatus.NONE, PlaybackStatus.fromSessionState(android.media.session.PlaybackState.STATE_NONE))
        assertEquals(PlaybackStatus.NONE, PlaybackStatus.fromSessionState(999))
        assertEquals("playing", PlaybackStatus.PLAYING.wire)
        assertTrue(PlaybackStatus.values().all { it.wire.isNotEmpty() })
    }
}
