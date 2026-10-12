package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 扫码配对地址优选(PairAddressPicker):同 /24 网段优先,否则保持二维码原序。 */
class PairAddressPickerTest {

    private val candidates = listOf("192.168.56.1", "192.168.1.100", "198.18.0.1")

    @Test
    fun `prefers address in same subnet as phone`() {
        assertEquals("192.168.1.100", PairAddressPicker.pick(candidates, "192.168.1.23"))
    }

    @Test
    fun `falls back to first candidate when no subnet matches`() {
        assertEquals("192.168.56.1", PairAddressPicker.pick(candidates, "10.20.30.40"))
    }

    @Test
    fun `falls back to first candidate when local ip unknown`() {
        assertEquals("192.168.56.1", PairAddressPicker.pick(candidates, null))
        assertEquals("192.168.56.1", PairAddressPicker.pick(candidates, ""))
    }

    @Test
    fun `subnet prefix validation`() {
        assertEquals("192.168.1", PairAddressPicker.subnetPrefixOf("192.168.1.23"))
        assertNull(PairAddressPicker.subnetPrefixOf("fe80::1"))
        assertNull(PairAddressPicker.subnetPrefixOf("999.1.1.1"))
        assertNull(PairAddressPicker.subnetPrefixOf("1.2.3"))
    }

    @Test
    fun `empty candidates return null`() {
        assertNull(PairAddressPicker.pick(emptyList(), "192.168.1.23"))
    }
}
