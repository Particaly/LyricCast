package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Test

/** 主界面连接状态映射(ConnectionUi.from):LanConnectionState 状态文本 → UI 状态。 */
class ConnectionUiStatusTest {

    @Test
    fun `connected text maps to connected`() {
        assertEquals(ConnectionUiStatus.CONNECTED, ConnectionUi.from(true, "已连接"))
    }

    @Test
    fun `connecting text maps to connecting`() {
        assertEquals(
            ConnectionUiStatus.CONNECTING,
            ConnectionUi.from(false, "连接中 192.168.1.100:18765 …")
        )
    }

    @Test
    fun `disconnected text maps to reconnecting`() {
        assertEquals(
            ConnectionUiStatus.RECONNECTING,
            ConnectionUi.from(false, "已断开(closed(1000))，自动重连中…")
        )
    }

    @Test
    fun `auth failure wins over other signals`() {
        assertEquals(
            ConnectionUiStatus.AUTH_FAILED,
            ConnectionUi.from(false, "认证失败：请检查两端 Token 是否一致")
        )
    }

    @Test
    fun `disabled text maps to disabled`() {
        assertEquals(ConnectionUiStatus.DISABLED, ConnectionUi.from(false, "未启用"))
    }

    @Test
    fun `exited text maps to exited`() {
        assertEquals(ConnectionUiStatus.EXITED, ConnectionUi.from(false, "已退出"))
    }

    @Test
    fun `disabled text maps to needs access when listener access missing`() {
        assertEquals(
            ConnectionUiStatus.NEEDS_ACCESS,
            ConnectionUi.from(false, "未启用", listenerAccessGranted = false)
        )
    }

    @Test
    fun `needs access does not mask established or auth-failed states`() {
        assertEquals(
            ConnectionUiStatus.CONNECTED,
            ConnectionUi.from(true, "已连接", listenerAccessGranted = false)
        )
        assertEquals(
            ConnectionUiStatus.AUTH_FAILED,
            ConnectionUi.from(false, "认证失败：请检查两端 Token 是否一致", listenerAccessGranted = false)
        )
    }

    @Test
    fun `unknown text falls back to reconnecting when not connected`() {
        assertEquals(ConnectionUiStatus.RECONNECTING, ConnectionUi.from(false, "whatever"))
    }
}
