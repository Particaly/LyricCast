package com.saihgupr.androidrelay

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log

/**
 * 平台歌曲 ID 诊断(任务书阶段 5;默认关闭,仅写本地日志,不推送任何数据)。
 *
 * 目的:验证各音乐 App 的 MediaSession 元数据里到底有什么——尤其是
 * METADATA_KEY_MEDIA_ID 是否携带平台歌曲 ID(网易云/QQ 音乐等),
 * 以及 metadata/controller/playbackState 三处 extras Bundle 中是否藏着平台 ID。
 *
 * 用法:设置页开启「媒体会话诊断日志」后播放歌曲,过滤 logcat tag [RelayDiag]:
 *   adb logcat -s RelayDiag
 * 把各音乐 App 的实测输出反馈到 issue,决定 fetchById 的接入顺序。
 *
 * 安全:只输出 mediaId/extras 的 key 与基本类型值;位图(Art/AlbumArt)只打印"存在"
 * 不解码;绝不输出配对 Token。日志级别 Log.d(不进 release 混淆过滤之外的通道)。
 */
object MediaDiagnostics {

    private const val TAG = "RelayDiag"

    /** 总开关是否开启(读 LanSettings,默认 false) */
    fun enabled(context: Context): Boolean = LanSettings.isDiagnosticLoggingEnabled(context)

    /**
     * 打印一次完整的会话元数据诊断。每首歌/每次状态变化都会触发一次,
     * 输出较长,仅在开关打开时调用。
     *
     * @param controller 当前活跃的 MediaController
     */
    fun logSession(controller: MediaController?) {
        if (controller == null) return
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val pkg = controller.packageName

        Log.d(TAG, "===== session[$pkg] =====")
        logMetadata(pkg, metadata)
        logBundle(pkg, "controller.extras", controller.extras)
        logBundle(pkg, "playbackState.extras", playbackState?.extras)
        Log.d(TAG, "===== end[$pkg] =====")
    }

    private fun logMetadata(pkg: String, metadata: MediaMetadata?) {
        if (metadata == null) {
            Log.d(TAG, "[$pkg] metadata = null")
            return
        }
        Log.d(TAG, "[$pkg] mediaId = ${metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)}")
        for (key in metadata.keySet()) {
            when (key) {
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON ->
                    Log.d(TAG, "[$pkg] metadata.$key = <bitmap omitted>")
                else -> {
                    // getString 覆盖文本类;数值类字段用 getLong 兜底
                    val text = metadata.getString(key)
                    if (text != null) {
                        Log.d(TAG, "[$pkg] metadata.$key = ${describe(text)}")
                    } else {
                        Log.d(TAG, "[$pkg] metadata.$key = <number> ${metadata.getLong(key)}")
                    }
                }
            }
        }
    }

    private fun logBundle(pkg: String, label: String, bundle: Bundle?) {
        if (bundle == null || bundle.isEmpty) {
            Log.d(TAG, "[$pkg] $label = empty")
            return
        }
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            Log.d(TAG, "[$pkg] $label.$key = ${value?.let { "${it.javaClass.simpleName}: $it" } ?: "null"}")
        }
    }

    /** 长文本截断(封面 URI/大段描述避免刷屏),换行折叠 */
    private fun describe(text: String): String = text.replace('\n', ' ').let { if (it.length > 120) "${it.take(120)}…" else it }
}
