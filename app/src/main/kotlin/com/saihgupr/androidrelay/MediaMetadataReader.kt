package com.saihgupr.androidrelay

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState

/**
 * 把 framework 的 MediaController / MediaMetadata / PlaybackState 读取为 [MediaInfo]。
 *
 * 兼容性说明(实测各音乐 App 元数据质量参差,读取必须健壮、绝不抛异常):
 * - artist 为空是常见情况(部分 App 只填 albumArtist 或把 "歌名 - 歌手" 整个塞进 title),
 *   读取时 artist 为空会回退到 albumArtist,LAN 通道按"尽量非空"的 artist 推送;
 * - 封面可能以位图(ALBUM_ART/ART/DISPLAY_ICON)或 URI(ALBUM_ART_URI/ART_URI/DISPLAY_ICON_URI)
 *   两种形式存在,这里只探测"是否存在",位图本体由发送方按需提取,避免每次回调解码大图;
 * - metadata / playbackState 任一为 null 都不会失败,相应字段用安全默认值填充。
 */
object MediaMetadataReader {

    /**
     * 从一个活跃会话读取完整媒体信息。
     *
     * @param controller 当前活跃的 MediaController
     * @param appLabel 来源应用显示名;传 null 时回落为包名
     * @return 快照;controller 本身为 null 时返回 null(理论上不发生,防御性处理)
     */
    fun read(controller: MediaController?, appLabel: String?): MediaInfo? {
        if (controller == null) return null
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val pkg = controller.packageName

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val rawArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        // LAN 通道使用"尽量非空"的 artist(见类注释)
        val artist = rawArtist.ifBlank { albumArtist }
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val genre = metadata?.getString(MediaMetadata.METADATA_KEY_GENRE) ?: ""
        val year = metadata?.getLong(MediaMetadata.METADATA_KEY_YEAR) ?: 0L
        val trackNumber = metadata?.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) ?: 0L
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""

        val (artworkAvailable, artworkUri) = detectArtwork(metadata)

        val track = TrackInfo(
            id = TrackInfo.stableId(title, artist, album, durationMs),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            durationMs = durationMs,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            mediaId = mediaId,
            artworkAvailable = artworkAvailable,
            artworkUri = artworkUri
        )

        val status = if (playbackState == null) {
            PlaybackStatus.NONE
        } else {
            PlaybackStatus.fromSessionState(playbackState.state)
        }
        val rawPosition = playbackState?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val playback = PlaybackInfo(
            status = status,
            positionMs = rawPosition,
            positionKnown = rawPosition != PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            speed = playbackState?.playbackSpeed ?: 0f,
            positionUpdateTimeMs = playbackState?.lastPositionUpdateTime ?: 0L
        )

        return MediaInfo(
            appPackage = pkg,
            appName = appLabel ?: pkg,
            track = track,
            playback = playback
        )
    }

    /**
     * 按任务书约定的优先级探测封面:ALBUM_ART → ART → ALBUM_ART_URI → ART_URI →
     * DISPLAY_ICON → DISPLAY_ICON_URI。
     *
     * 只做 containsKey / getString 级别的廉价探测,不在此处解码位图。
     *
     * @return (是否可用, URI 形式的封面地址或 null)
     */
    private fun detectArtwork(metadata: MediaMetadata?): Pair<Boolean, String?> {
        if (metadata == null) return false to null
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART)) return true to null
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_ART)) return true to null
        val albumArtUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        if (!albumArtUri.isNullOrBlank()) return true to albumArtUri
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (!artUri.isNullOrBlank()) return true to artUri
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_ICON)) return true to null
        val iconUri = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (!iconUri.isNullOrBlank()) return true to iconUri
        return false to null
    }
}
