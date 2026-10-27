package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
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
 * 局域网节点发现。
 *
 * 用户要的是「点一下找到家人」，所以不能让人手输 IP。做法朴素但在家用 WiFi 上最稳：
 *  1. 列出本机所有网卡的 IPv4 + 掩码，推导 /24 网段（家用路由器基本都是 /24）；
 *  2. 网段内每地址做 TCP connect 探测（250ms 超时，并发 48）；
 *  3. 只对连通的地址再打 `/mesh/hello` 确认是不是我们的节点。
 *
 * **为什么不用 mDNS/NSD**：Android 的 NsdManager 在同一网络里经常被路由器多播隔离，
 * 且跨设备版本差异大（有的设备 mDNS 解析要 10s+）。TCP 扫描 254 个地址在本机 WiFi 上
 * 通常 2–4 秒出结果，体验更可控。
 *
 * 也不做 UDP 广播：会被部分路由器的 AP isolation 干掉，排查成本高。
 *
 * P2P 下这个函数比主机模式更重要：没有「中心」可以让你记住地址，
 * 每次换网络都得重新发现一遍。
 */
class LanDiscovery(private val ctx: Context, private val settings: SettingsStore) {

    private val api by lazy { MeshApi(settings) }

    /**
     * @param selfNodeId 本机 nodeId。扫到的自己会被标记出来而不是隐藏 ——
     *   用户看到「这台设备」比「列表里少了一台」更容易理解
     */
    suspend fun scan(selfNodeId: String = ""): List<DiscoveredNode> = coroutineScope {
        val port = settings.get().serverPort
        val subnets = localSubnets()
        if (subnets.isEmpty()) return@coroutineScope emptyList()

        val targets = subnets.flatMap { (prefix, ownSuffix) ->
            (1..254).filter { it != ownSuffix }.map { "$prefix.$it" }
        }.distinct().take(512)

        // 先 TCP 探活，再打 HTTP：直接对 254 个地址发 HTTP 会在
        // 不存在的 IP 上耗满超时，TCP connect 拒绝要快一个数量级
        val alive = targets.chunked(48).flatMap { chunk ->
            chunk.map { ip -> async(Dispatchers.IO) { probe(ip, port) } }.awaitAll()
        }.filterNotNull()

        alive.mapNotNull { address ->
            withTimeoutOrNull(2500) { api.hello(address, port) }
        }.distinctBy { it.nodeId.ifBlank { it.address } }
    }

    /** 只探活，不确认身份 */
    private fun probe(ip: String, port: Int): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 250)
            ip
        }
    }.getOrNull()

    /**
     * 本机所有网卡的 /24 网段前缀与自己的末位。
     *
     * 不用 `WifiManager.getConnectionInfo()`：Android 12 之后受隐私限制拿不到可靠网段，
     * 直接从网卡枚举更稳，也能一并覆盖热点/以太网/USB 共享这些非常规连接。
     */
    private fun localSubnets(): List<Pair<String, Int>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { addr ->
                val host = addr.hostAddress ?: return@mapNotNull null
                val parts = host.split('.')
                if (parts.size != 4) return@mapNotNull null
                "${parts[0]}.${parts[1]}.${parts[2]}" to (parts[3].toIntOrNull() ?: return@mapNotNull null)
            }
            .distinct()
    }.getOrDefault(emptyList())
}
