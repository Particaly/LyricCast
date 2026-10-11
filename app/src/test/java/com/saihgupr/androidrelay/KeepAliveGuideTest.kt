package com.saihgupr.androidrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保活引导纯逻辑测试:ROM 识别、自检聚合与引导步骤顺序(参考 Gotify 的保活引导)。 */
class KeepAliveGuideTest {

    // ==================== RomFlavor.detect ====================

    @Test
    fun `detects major roms from manufacturer`() {
        assertEquals(RomFlavor.XIAOMI, RomFlavor.detect("Xiaomi"))
        assertEquals(RomFlavor.HUAWEI, RomFlavor.detect("HUAWEI"))
        assertEquals(RomFlavor.HONOR, RomFlavor.detect("HONOR"))
        assertEquals(RomFlavor.OPPO, RomFlavor.detect("OPPO"))
        assertEquals(RomFlavor.OPPO, RomFlavor.detect("OnePlus"))
        assertEquals(RomFlavor.OPPO, RomFlavor.detect("realme"))
        assertEquals(RomFlavor.VIVO, RomFlavor.detect("vivo"))
        assertEquals(RomFlavor.SAMSUNG, RomFlavor.detect("samsung"))
        assertEquals(RomFlavor.MEIZU, RomFlavor.detect("Meizu"))
    }

    @Test
    fun `detects sub-brands from brand field`() {
        assertEquals(RomFlavor.XIAOMI, RomFlavor.detect("Xiaomi", "Redmi"))
        assertEquals(RomFlavor.XIAOMI, RomFlavor.detect("Xiaomi", "POCO"))
        assertEquals(RomFlavor.HONOR, RomFlavor.detect("HUAWEI", "HONOR"))
        assertEquals(RomFlavor.VIVO, RomFlavor.detect("vivo", "iQOO"))
        assertEquals(RomFlavor.OPPO, RomFlavor.detect("OPPO", "OnePlus"))
    }

    @Test
    fun `detection is case-insensitive`() {
        assertEquals(RomFlavor.XIAOMI, RomFlavor.detect("XIAOMI"))
        assertEquals(RomFlavor.SAMSUNG, RomFlavor.detect("SAMSUNG"))
        assertEquals(RomFlavor.MEIZU, RomFlavor.detect("MEIZU"))
    }

    @Test
    fun `honor wins over huawei when both match`() {
        // 荣耀独立后部分机型厂商/品牌字段同时含两者,识别顺序上荣耀优先
        assertEquals(RomFlavor.HONOR, RomFlavor.detect("HONOR", "HONOR"))
    }

    @Test
    fun `unknown or blank manufacturers fall back to stock`() {
        assertEquals(RomFlavor.STOCK, RomFlavor.detect("Google"))
        assertEquals(RomFlavor.STOCK, RomFlavor.detect("ZTE"))
        assertEquals(RomFlavor.STOCK, RomFlavor.detect(null))
        assertEquals(RomFlavor.STOCK, RomFlavor.detect(""))
        assertEquals(RomFlavor.STOCK, RomFlavor.detect(null, null))
    }

    @Test
    fun `non-stock roms expose autostart entries while stock does not`() {
        for (flavor in RomFlavor.values()) {
            if (flavor == RomFlavor.STOCK) {
                assertTrue("$flavor 不应有深链", flavor.autostartEntries.isEmpty())
                assertFalse("$flavor 不应要求自启动确认", flavor.needsAutostart)
                assertFalse("$flavor 不应要求加锁确认", flavor.needsLockHint)
            } else {
                assertTrue("$flavor 应有自启动入口", flavor.autostartEntries.isNotEmpty())
                for (entry in flavor.autostartEntries) {
                    assertTrue("$flavor 深链格式应为 包名/Activity: $entry", entry.contains('/'))
                }
            }
        }
    }

    // ==================== KeepAliveCheck.evaluate ====================

    @Test
    fun `stock rom treats autostart and lock as done regardless of confirmation`() {
        val check = KeepAliveCheck.evaluate(
            batteryOk = true, notificationOk = true,
            autostartConfirmed = false, recentsConfirmed = false, flavor = RomFlavor.STOCK
        )
        assertTrue(check.allDone)
        assertTrue(check.pending().isEmpty())
    }

    @Test
    fun `pending steps follow battery, autostart, lock, notification order`() {
        val check = KeepAliveCheck.evaluate(
            batteryOk = false, notificationOk = false,
            autostartConfirmed = false, recentsConfirmed = false, flavor = RomFlavor.XIAOMI
        )
        assertEquals(
            listOf(
                KeepAliveStep.BATTERY,
                KeepAliveStep.AUTOSTART,
                KeepAliveStep.LOCK_RECENTS,
                KeepAliveStep.NOTIFICATION
            ),
            check.pending()
        )
    }

    @Test
    fun `user confirmation satisfies only unverifiable items`() {
        val check = KeepAliveCheck.evaluate(
            batteryOk = true, notificationOk = true,
            autostartConfirmed = true, recentsConfirmed = false, flavor = RomFlavor.HUAWEI
        )
        assertEquals(listOf(KeepAliveStep.LOCK_RECENTS), check.pending())
    }

    @Test
    fun `battery and notification always need real checks`() {
        val check = KeepAliveCheck.evaluate(
            batteryOk = false, notificationOk = false,
            autostartConfirmed = true, recentsConfirmed = true, flavor = RomFlavor.XIAOMI
        )
        assertEquals(
            listOf(KeepAliveStep.BATTERY, KeepAliveStep.NOTIFICATION),
            check.pending()
        )
        assertFalse(check.allDone)
    }

    @Test
    fun `all done yields empty pending and allDone flag`() {
        val check = KeepAliveCheck.evaluate(
            batteryOk = true, notificationOk = true,
            autostartConfirmed = true, recentsConfirmed = true, flavor = RomFlavor.XIAOMI
        )
        assertTrue(check.allDone)
        assertTrue(check.pending().isEmpty())
    }
}
