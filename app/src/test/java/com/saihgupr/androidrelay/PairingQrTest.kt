package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配对二维码 payload(docs/protocol.md §7)解析的纯逻辑测试。 */
class PairingQrTest {

    private val token = "aBcDeFgHiJkLmNoPqRsTuVwx" // 24 位,与插件端生成格式一致

    @Test
    fun `parses full payload with two addresses`() {
        val info = PairingQr.parse(
            "androidrelay://pair?v=1&port=18765&ip=192.168.1.5&ip=10.0.0.7&token=$token"
        )
        assertEquals(listOf("192.168.1.5", "10.0.0.7"), info?.addresses)
        assertEquals(18765, info?.port)
        assertEquals(token, info?.token)
        assertEquals("192.168.1.5", info?.primaryHost)
    }

    @Test
    fun `round-trips through build`() {
        val payload = PairingQr.build(listOf("192.168.1.5", "10.0.0.7"), 18765, token)
        val info = PairingQr.parse(payload)
        assertEquals(listOf("192.168.1.5", "10.0.0.7"), info?.addresses)
        assertEquals(18765, info?.port)
        assertEquals(token, info?.token)
    }

    @Test
    fun `missing version and port fall back to v1 and default port`() {
        val info = PairingQr.parse("androidrelay://pair?ip=192.168.1.5&token=$token")
        assertEquals(LanSettings.DEFAULT_PORT, info?.port)
    }

    @Test
    fun `deduplicates repeated addresses while keeping order`() {
        val info = PairingQr.parse(
            "androidrelay://pair?ip=192.168.1.5&ip=192.168.1.5&ip=10.0.0.7&token=$token"
        )
        assertEquals(listOf("192.168.1.5", "10.0.0.7"), info?.addresses)
    }

    @Test
    fun `rejects unrelated text and other schemes`() {
        assertNull(PairingQr.parse("hello world"))
        assertNull(PairingQr.parse("https://example.com/pair?v=1&ip=192.168.1.5&token=$token"))
        assertNull(PairingQr.parse("androidrelay://other?ip=192.168.1.5&token=$token"))
    }

    @Test
    fun `rejects payload without token or addresses`() {
        assertNull(PairingQr.parse("androidrelay://pair?v=1&port=18765&ip=192.168.1.5"))
        assertNull(PairingQr.parse("androidrelay://pair?v=1&port=18765&token=$token"))
    }

    @Test
    fun `rejects short or malformed token`() {
        assertNull(PairingQr.parse("androidrelay://pair?ip=192.168.1.5&token=short"))
        assertNull(PairingQr.parse("androidrelay://pair?ip=192.168.1.5&token=${"包".repeat(20)}"))
    }

    @Test
    fun `rejects invalid port`() {
        assertNull(PairingQr.parse("androidrelay://pair?port=0&ip=192.168.1.5&token=$token"))
        assertNull(PairingQr.parse("androidrelay://pair?port=70000&ip=192.168.1.5&token=$token"))
        assertNull(PairingQr.parse("androidrelay://pair?port=abc&ip=192.168.1.5&token=$token"))
    }

    @Test
    fun `rejects invalid ipv4 but keeps valid ones`() {
        val info = PairingQr.parse(
            "androidrelay://pair?ip=999.1.1.1&ip=192.168.1.5&ip=abc&token=$token"
        )
        assertEquals(listOf("192.168.1.5"), info?.addresses)
    }

    @Test
    fun `rejects unsupported future version`() {
        assertNull(PairingQr.parse("androidrelay://pair?v=2&ip=192.168.1.5&token=$token"))
    }

    @Test
    fun `rejects oversized payload`() {
        val long = "androidrelay://pair?" + "x".repeat(600)
        assertNull(PairingQr.parse(long))
    }

    @Test
    fun `accepts scheme and host case-insensitively`() {
        val info = PairingQr.parse("AndroidRelay://Pair?ip=192.168.1.5&token=$token")
        assertTrue(info != null)
        assertEquals("192.168.1.5", info?.primaryHost)
    }

    @Test
    fun `parses multi nic payload with virtual adapter first`() {
        // 模拟 Windows 多网卡枚举:VMware 宿主网卡排在真实网卡之前(依赖
        // PairAddressPicker 在落地时优选同网段地址,见 PairAddressPickerTest)
        val info = PairingQr.parse(
            "androidrelay://pair?v=1&port=18765&ip=192.168.56.1&ip=192.168.1.100&ip=198.18.0.1&token=$token"
        )
        assertEquals(
            listOf("192.168.56.1", "192.168.1.100", "198.18.0.1"),
            info?.addresses
        )
    }

    @Test
    fun `rejects download url qr content`() {
        // 插件设置页另有"下载二维码"(GitHub Release 链接),误扫时应明确拒收
        assertNull(
            PairingQr.parse(
                "https://github.com/ZToolsCenter/LyricCast/releases/latest/download/LyricCast.apk"
            )
        )
    }
}
