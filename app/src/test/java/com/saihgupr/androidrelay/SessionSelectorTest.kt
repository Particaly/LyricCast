package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多会话选择与黑/白名单过滤的纯逻辑测试。 */
class SessionSelectorTest {

    private fun candidate(pkg: String, playing: Boolean = false, activeAt: Long = 0L) =
        SessionSelector.Candidate(pkg, playing, activeAt)

    @Test
    fun `playing session wins over more recently active paused one`() {
        val selected = SessionSelector.select(
            listOf(
                candidate("app.music", playing = false, activeAt = 1000),
                candidate("app.other.music", playing = true, activeAt = 0)
            )
        )
        assertEquals("app.other.music", selected?.packageName)
    }

    @Test
    fun `most recently active playing session wins among playing`() {
        val selected = SessionSelector.select(
            listOf(
                candidate("app.a", playing = true, activeAt = 100),
                candidate("app.b", playing = true, activeAt = 900)
            )
        )
        assertEquals("app.b", selected?.packageName)
    }

    @Test
    fun `falls back to most recently active when nothing plays`() {
        val selected = SessionSelector.select(
            listOf(
                candidate("app.a", playing = false, activeAt = 5),
                candidate("app.b", playing = false, activeAt = 50),
                candidate("app.c", playing = false, activeAt = 10)
            )
        )
        assertEquals("app.b", selected?.packageName)
    }

    @Test
    fun `blacklist excludes packages even when playing`() {
        val selected = SessionSelector.select(
            listOf(
                candidate("com.video.app", playing = true, activeAt = 999),
                candidate("com.music.app", playing = false, activeAt = 1)
            ),
            blacklist = setOf("com.video.app")
        )
        assertEquals("com.music.app", selected?.packageName)
    }

    @Test
    fun `all filtered out yields null`() {
        val selected = SessionSelector.select(
            listOf(candidate("com.video.app", playing = true)),
            blacklist = setOf("com.video.app")
        )
        assertNull(selected)
        assertNull(SessionSelector.select(emptyList()))
    }

    @Test
    fun `non-empty whitelist takes precedence over blacklist`() {
        val passes = SessionSelector.passesFilter(
            packageName = "com.music.app",
            blacklist = setOf("com.music.app"),
            whitelist = setOf("com.music.app")
        )
        assertTrue(passes)
    }

    @Test
    fun `package filter is case insensitive`() {
        assertTrue(
            SessionSelector.passesFilter(
                "COM.Music.App",
                blacklist = setOf("com.music.app"),
                whitelist = emptySet()
            ).not()
        )
        assertTrue(
            SessionSelector.passesFilter(
                "com.music.app",
                blacklist = emptySet(),
                whitelist = setOf("COM.MUSIC.APP")
            )
        )
    }

    @Test
    fun `empty black and white lists allow everything`() {
        assertTrue(SessionSelector.passesFilter("any.app", emptySet(), emptySet()))
    }
}

/** track.id 稳定标识的纯逻辑测试。 */
class TrackIdTest {

    @Test
    fun `id is deterministic and case-whitespace insensitive`() {
        val a = TrackInfo.stableId("晴天", "周杰伦", "叶惠美", 269_000)
        val b = TrackInfo.stableId(" 晴天 ", "周杰伦", "叶惠美", 269_000)
        val c = TrackInfo.stableId("晴天", "周杰伦", "叶惠美", 269_000)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `id is sha1 hex and differs on any component change`() {
        val base = TrackInfo.stableId("title", "artist", "album", 200_000)
        assertEquals(40, base.length)
        assertTrue(base.matches(Regex("[0-9a-f]{40}")))
        assertNotEquals(base, TrackInfo.stableId("title2", "artist", "album", 200_000))
        assertNotEquals(base, TrackInfo.stableId("title", "artist2", "album", 200_000))
        assertNotEquals(base, TrackInfo.stableId("title", "artist", "album2", 200_000))
        assertNotEquals(base, TrackInfo.stableId("title", "artist", "album", 200_001))
    }

    @Test
    fun `empty metadata still produces a stable id`() {
        val a = TrackInfo.stableId("", "", "", 0)
        assertEquals(a, TrackInfo.stableId("", "", "", 0))
        assertFalse(a.isBlank())
    }
}
