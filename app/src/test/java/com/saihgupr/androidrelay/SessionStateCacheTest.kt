package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话状态缓存测试(任务书阶段 7:"断线期间只保留最新状态")。
 * 协议 §4:断连期间事件不排队,同包名覆盖式更新;重连后以最新值发 snapshot。
 */
class SessionStateCacheTest {

    private fun info(
        pkg: String,
        status: PlaybackStatus = PlaybackStatus.PLAYING,
        positionMs: Long = 10_000
    ) = MediaInfo(
        appPackage = pkg,
        appName = pkg,
        track = TrackInfo(
            id = "$pkg-track",
            title = "t", artist = "a", album = "", albumArtist = "",
            durationMs = 200_000, genre = "", year = 0, trackNumber = 0, mediaId = "",
            artworkAvailable = false, artworkUri = null
        ),
        playback = PlaybackInfo(
            status = status,
            positionMs = positionMs,
            positionKnown = true,
            speed = 1f,
            positionUpdateTimeMs = 0
        )
    )

    @Test
    fun `repeated events for same package keep only the latest`() {
        val cache = SessionStateCache()
        cache.update(info("music", positionMs = 1_000), 100)
        cache.update(info("music", positionMs = 2_000), 200)
        cache.update(info("music", positionMs = 3_000), 300)

        assertEquals(1, cache.size())
        assertEquals(3_000L, cache.latestOf("music")?.playback?.positionMs)
    }

    @Test
    fun `select follows playing session and caches selection`() {
        val cache = SessionStateCache()
        cache.update(info("video", PlaybackStatus.PAUSED), 500)
        cache.update(info("music", PlaybackStatus.PLAYING), 100)

        val selected = cache.select(blacklist = emptySet(), whitelist = emptySet())

        assertEquals("music", selected?.appPackage)
        assertEquals("music", cache.selectedPackage)
        assertEquals("music", cache.selectedInfo()?.appPackage)
    }

    @Test
    fun `select falls back to most recent when nothing plays`() {
        val cache = SessionStateCache()
        cache.update(info("podcast", PlaybackStatus.PAUSED, positionMs = 1), 100)
        cache.update(info("video", PlaybackStatus.PAUSED, positionMs = 2), 900)

        assertEquals("video", cache.select(emptySet(), emptySet())?.appPackage)
    }

    @Test
    fun `all filtered out yields null and clears selection`() {
        val cache = SessionStateCache()
        cache.update(info("banned"), 100)
        assertNull(cache.select(blacklist = setOf("banned"), whitelist = emptySet()))
        assertNull(cache.selectedInfo())
    }

    @Test
    fun `anyInfo returns the last session for empty-snapshot metadata`() {
        val cache = SessionStateCache()
        assertNull(cache.anyInfo())
        cache.update(info("music", PlaybackStatus.STOPPED), 100)
        assertEquals("music", cache.anyInfo()?.appPackage)
    }
}
