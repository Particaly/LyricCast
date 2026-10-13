package com.saihgupr.androidrelay

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 扫码配对的地址挑选(纯 JVM 实现,JVM 单测可直接覆盖)。
 *
 * 配对二维码按 PC 的网卡枚举顺序编入全部局域网 IPv4,
 * Windows 上虚拟网卡(VMware/WSL/Clash TUN 等)常常排在真实网卡前面,
 * 直接取第一个会填入连不上的地址。这里按"与手机同一 /24 网段优先"重选:
 * - 手机连着同一 Wi-Fi 时几乎总能命中电脑的真实地址;
 * - 命中不了(热点隔离/枚举失败/PC 跨网段多网卡)则保持二维码原序的第一位,
 *   其余地址作为备选交给 [LanPushClient] 在重连时轮换尝试。
 */
object PairAddressPicker {

    /**
     * 从二维码候选里选出应填入"PC 地址"的地址。
     * @param addresses 二维码中的全部 IP(已按 payload 顺序去重)
     * @param localIp 手机当前的局域网 IPv4(取不到传 null)
     * @return 选中的地址;候选为空时返回 null
     */
    fun pick(addresses: List<String>, localIp: String?): String? {
        if (addresses.isEmpty()) return null
        if (localIp.isNullOrBlank()) return addresses.first()
        val subnet = subnetPrefixOf(localIp) ?: return addresses.first()
        return addresses.firstOrNull { subnetPrefixOf(it) == subnet } ?: addresses.first()
    }

    /**
     * 手机当前的局域网 IPv4:枚举本机网卡(java.net,无需定位权限),
     * 取第一个"已启用 + 非回环 + 局域网私有段(10/172.16/192.168)"的地址;取不到返回 null。
     */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .map { it.hostAddress }
            .firstOrNull()
    } catch (_: Exception) {
        null
    }

    /** IPv4 的 /24 前缀("192.168.1.5" → "192.168.1");格式非法返回 null。 */
    fun subnetPrefixOf(ip: String): String? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val valid = parts.all { segment ->
            segment.isNotEmpty() && segment.length <= 3 &&
                segment.all(Char::isDigit) && segment.toInt() in 0..255
        }
        return if (valid) parts.take(3).joinToString(".") else null
    }
}
