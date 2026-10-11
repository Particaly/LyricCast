package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Test

/** 前台服务通知文案映射(任务书阶段 2:已连接 / 重连中 / 未配对)。 */
class ForegroundStatusTest {

    @Test
    fun `lan disabled shows unpaired`() {
        assertEquals("未配对:LAN 推送未启用", ForegroundStatus.label(false, true, "已连接"))
        assertEquals("未配对:LAN 推送未启用", ForegroundStatus.label(false, false, "连接中 …"))
    }

    @Test
    fun `connected shows connected with detail`() {
        assertEquals("已连接 · 已连接", ForegroundStatus.label(true, true, "已连接"))
    }

    @Test
    fun `disconnected shows reconnecting with reason`() {
        val text = ForegroundStatus.label(true, false, "已断开(closed(1000)),自动重连中…")
        assertEquals("重连中 · 已断开(closed(1000)),自动重连中…", text)
    }

    @Test
    fun `auth failure passes through verbatim`() {
        val text = "认证失败:请检查 Token"
        assertEquals(text, ForegroundStatus.label(true, false, text))
    }
}
