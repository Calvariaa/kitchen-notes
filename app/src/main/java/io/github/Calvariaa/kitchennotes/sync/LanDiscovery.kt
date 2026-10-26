package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import io.github.Calvariaa.kitchennotes.data.DiscoveredHost
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 局域网扫描。
 *
 * 用户要的是「点一下加入家庭组」，所以不能让人手输 IP。
 * 做法非常朴素但在这个场景最稳：
 *  1. 列出本机所有网卡的 IPv4 + 掩码，推导出 /24 网段（家用路由器基本都是 /24）。
 *  2. 对网段内每个地址做 TCP connect 探测（超时 250ms，并发 48），
 *     只对连通的地址再打 `/api/info` 确认是不是我们的主机。
 *
 * 为什么不用 mDNS/NSD：Android 的 NsdManager 在同一网络里经常被路由器多播隔离，
 * 而且跨设备版本差异大（有的设备 mDNS 解析超时 10s+）。TCP 扫描 254 个地址在本机 WiFi 上
 * 通常 2-4 秒出结果，体验更可控，且不依赖多播。
 *
 * 也不做 UDP 广播：会被部分路由器的 AP isolation 干掉，排查成本高。
 */
class LanDiscovery(private val ctx: Context, private val settings: SettingsStore) {

    suspend fun scan(port: Int = settings.get().serverPort): List<DiscoveredHost> = coroutineScope {
        val api = MembersApi(settings)
        val subnets = localSubnets()
        if (subnets.isEmpty()) return@coroutineScope emptyList()

        val targets = subnets.flatMap { (prefix, ownSuffix) ->
            (1..254).filter { it != ownSuffix }.map { "$prefix.$it" }
        }.distinct().take(512)

        val found = targets.chunked(48).flatMap { chunk ->
            chunk.map { ip ->
                async(Dispatchers.IO) { probeHost(ip, port) }
            }.awaitAll()
        }.filterNotNull()

        // 同一次扫描内自查：只对真正回应 /api/info 的打标签
        found.mapNotNull { ip ->
            val info = withTimeoutOrNull(2500) { api.probeInfo("$ip:$port") } ?: return@mapNotNull null
            DiscoveredHost(
                address = "$ip:$port",
                groupName = info.groupName,
                hostName = info.hostName,
                memberCount = info.memberCount,
                requireToken = info.requireToken,
            )
        }.distinctBy { it.address }
    }

    /** 先 TCP 探活，比直接打 HTTP 快一个数量级（不建连接的三次握手 + 无应用层等待） */
    private fun probeHost(ip: String, port: Int): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 250)
            socket.soTimeout = 250
            ip
        }
    }.getOrNull()

    /** 取本机所有 /24 网段前缀，附带「自己在这段里的主机号」用于跳过 */
    fun localSubnets(): List<Pair<String, Int>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { nif ->
                val iface = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
                    ?: return@mapNotNull null
                val addr = iface.hostAddress ?: return@mapNotNull null
                // 虚拟网卡（VPN、WSL、热点软 AP）网段扫了也没意义，且拖慢速度
                if (addr.startsWith("169.254")) return@mapNotNull null
                val parts = addr.split(".")
                if (parts.size != 4) return@mapNotNull null
                "${parts[0]}.${parts[1]}.${parts[2]}" to parts[3].toIntOrNull().orZero()
            }
            .distinct()
    }.getOrDefault(emptyList())

    private fun Int?.orZero(): Int = this ?: 0
}
