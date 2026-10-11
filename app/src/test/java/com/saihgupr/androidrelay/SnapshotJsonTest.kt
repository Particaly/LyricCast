package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议消息组装测试(任务书阶段 7:snapshot 构造;docs/protocol.md §2.3/§2.8)。 */
class SnapshotJsonTest {

    private fun track(id: String = "t1") = TrackInfo(
        id = id,
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        albumArtist = "周杰伦",
        durationMs = 269_000,
        genre = "Pop",
        year = 2003,
        trackNumber = 3,
        mediaId = "m-1",
        artworkAvailable = true,
        artworkUri = null
    )

    private fun info(status: PlaybackStatus = PlaybackStatus.PLAYING) = MediaInfo(
        appPackage = "com.netease.cloudmusic",
        appName = "网易云音乐",
        track = track(),
        playback = PlaybackInfo(
            status = status,
            positionMs = 80_000,
            positionKnown = true,
            speed = 1.25f,
            positionUpdateTimeMs = 1_000
        )
    )

    @Test
    fun `state message carries type v seq reason and playback fields`() {
        val json = SnapshotJson.build(
            type = "state", seq = 7, sentAtMs = 123_456L, reason = "seek",
            info = info(), artworkHash = "abc"
        )
        assertEquals("state", json.getString("type"))
        assertEquals(1, json.getInt("v"))
        assertEquals(7L, json.getLong("seq"))
        assertEquals(123_456L, json.getLong("sentAt"))
        assertEquals("seek", json.getString("reason"))

        val playback = json.getJSONObject("playback")
        assertEquals("playing", playback.getString("state"))
        assertEquals(80_000, playback.getLong("positionMs"))
        assertTrue(playback.getBoolean("positionKnown"))
        assertEquals(1.25, playback.getDouble("speed"), 1e-9)

        assertEquals("com.netease.cloudmusic", json.getJSONObject("app").getString("package"))
        assertEquals("晴天", json.getJSONObject("track").getString("title"))
        assertEquals("abc", json.getJSONObject("artwork").getString("hash"))
    }

    @Test
    fun `snapshot message is isomorphic to state with same payload`() {
        val state = SnapshotJson.build("state", 1, 1L, "heartbeat", info(), "")
        val snapshot = SnapshotJson.build("snapshot", 1, 1L, "reconnect", info(), "")
        assertEquals("snapshot", snapshot.getString("type"))
        assertEquals(
            state.getJSONObject("track").toString(),
            snapshot.getJSONObject("track").toString()
        )
        assertEquals(
            state.getJSONObject("playback").toString(),
            snapshot.getJSONObject("playback").toString()
        )
    }

    @Test
    fun `unknown position is never faked`() {
        val unknown = info().copy(
            playback = info().playback.copy(positionMs = -1, positionKnown = false)
        )
        val json = SnapshotJson.build("state", 1, 1L, "heartbeat", unknown, "")
        val playback = json.getJSONObject("playback")
        assertEquals(-1L, playback.getLong("positionMs"))
        assertFalse(playback.getBoolean("positionKnown"))
    }

    @Test
    fun `none state keeps last track metadata for pc display`() {
        val none = info(PlaybackStatus.NONE)
        val json = SnapshotJson.build("snapshot", 1, 1L, "reconnect", none, "")
        assertEquals("none", json.getJSONObject("playback").getString("state"))
        assertEquals("晴天", json.getJSONObject("track").getString("title"))
    }
}
