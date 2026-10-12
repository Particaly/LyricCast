package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Test

/** 前台服务通知文案映射(任务书阶段 2:已连接 / 重连中 / 未配对)。 */
class ForegroundStatusTest {

    @Test
    fun `lan disabled shows unpaired`() {
        assertEquals("未配对 · LAN 推送未开启", ForegroundStatus.label(false, true, "已连接"))
        assertEquals("未配对 · LAN 推送未开启", ForegroundStatus.label(false, false, "连接中 …"))
    }

    @Test
    fun `connected shows connected with detail`() {
        assertEquals("已连接 · 正在同步播放信息", ForegroundStatus.label(true, true, "已连接"))
    }

    @Test
    fun `disconnected shows reconnecting with reason`() {
        val text = ForegroundStatus.label(true, false, "已断开(closed(1000))，自动重连中…")
        assertEquals("重连中 · 已断开(closed(1000))，自动重连中…", text)
    }

    @Test
    fun `auth failure passes through verbatim`() {
        val text = "认证失败：请检查两端 Token 是否一致"
        assertEquals(text, ForegroundStatus.label(true, false, text))
    }

    @Test
    fun `listener access missing shows pending authorization`() {
        assertEquals(
            "待授权 · 需开启通知使用权",
            ForegroundStatus.label(true, false, "未启用", listenerAccessGranted = false)
        )
    }

    @Test
    fun `enabled but service not started shows waiting`() {
        assertEquals("已保存 · 等待推送服务启动", ForegroundStatus.label(true, false, "未启用"))
    }
}
