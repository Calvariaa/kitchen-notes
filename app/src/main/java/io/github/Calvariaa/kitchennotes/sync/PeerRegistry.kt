package io.github.Calvariaa.kitchennotes.sync

import android.util.Log
import io.github.Calvariaa.kitchennotes.data.Peer
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已知节点名册。
 *
 * 它是**每台设备各自的视图**，不是全网共识：A 认识 B 不代表 C 认识 B，
 * 谁在线谁就被 `noteSeen` 刷新地址。合并靠 `nodeId`（公钥指纹），
 * 因为地址会变、名字能改，只有指纹不变。
 *
 * 为什么不放在 `family.json` 里：那份文件是「我属于哪个家」的稳定事实，
 * 而名册会因为设备上下线而频繁变动。混在一起写会让每次联网都重写家庭密钥
 * 所在的文件，一次写坏就是把全家锁在门外。
 */
class PeerRegistry(
    private val settings: SettingsStore,
) {
    @Volatile
    private var cache: List<Peer>? = null

    private fun load(): List<Peer> {
        cache?.let { return it }
        val loaded = settings.peers()
        cache = loaded
        return loaded
    }

    fun all(): List<Peer> = load().filter { it.nodeId.isNotBlank() }

    fun find(nodeId: String): Peer? = load().firstOrNull { it.nodeId == nodeId }

    fun findByEmail(email: String): Peer? =
        load().firstOrNull { it.email.equals(email, ignoreCase = true) }

    suspend fun upsert(peer: Peer) = withContext(Dispatchers.IO) {
        upsertBlocking(peer)
    }

    fun upsertBlocking(peer: Peer) {
        if (peer.nodeId.isBlank()) return
        val current = load()
        // 同 nodeId 覆盖；同邮箱但换设备（nodeId 变了）也覆盖 —— 一个邮箱一个人
        val next = current.filterNot {
            it.nodeId == peer.nodeId || (peer.email.isNotBlank() && it.email.equals(peer.email, true))
        } + peer
        commit(next)
    }

    /**
     * 记录「刚在对端 X 的地址上看到它」。
     *
     * 非挂起：调用方是 HTTP 处理器（同步上下文），每次请求都要更新，
     * 而且丢一次地址刷新最多是下次扫描重建一次名册，不值得为它引入协程边界。
     */
    fun touch(nodeId: String, address: String) {
        if (nodeId.isBlank() || address.isBlank()) return
        // 回环地址一定不是对端的真实局域网位置：只有 adb forward / 本机自连才会
        // 出现 127.0.0.1。写进名册会让之后所有同步都往自己身上打，
        // 而正确地址就这么被覆盖掉了 —— 表现为「能加入、之后就再也同步不动」。
        if (address.startsWith("127.") || address.startsWith("::1")) return
        val current = load()
        val existing = current.firstOrNull { it.nodeId == nodeId } ?: return
        if (existing.address == address &&
            System.currentTimeMillis() - existing.lastSeenAt < 60_000L
        ) {
            return // 一分钟内同地址不重写磁盘，扫描/轮询会反复打进来
        }
        commit(
            current.map {
                if (it.nodeId == nodeId) it.copy(address = address, lastSeenAt = System.currentTimeMillis())
                else it
            },
        )
    }

    /** 合并一批从对端名册拿到的节点。本地信息优先（地址更准），只补缺的 */
    suspend fun mergeRemote(incoming: List<Peer>) = withContext(Dispatchers.IO) {
        if (incoming.isEmpty()) return@withContext
        val current = load().toMutableList()
        var changed = false
        incoming.forEach { remote ->
            if (remote.nodeId.isBlank()) return@forEach
            val idx = current.indexOfFirst {
                it.nodeId == remote.nodeId ||
                    (remote.email.isNotBlank() && it.email.equals(remote.email, true))
            }
            if (idx < 0) {
                current += remote.copy(synced = false)
                changed = true
            } else {
                val local = current[idx]
                // 公钥以本地为准：换公钥＝换了身份，不该被对端悄悄覆盖
                val merged = local.copy(
                    name = local.name.ifBlank { remote.name },
                    email = local.email.ifBlank { remote.email },
                    address = local.address.ifBlank { remote.address },
                    publicKey = local.publicKey.ifBlank { remote.publicKey },
                )
                if (merged != local) {
                    current[idx] = merged
                    changed = true
                }
            }
        }
        if (changed) commit(current)
    }

    suspend fun remove(nodeId: String) = withContext(Dispatchers.IO) {
        commit(load().filterNot { it.nodeId == nodeId })
    }

    suspend fun markSynced(nodeId: String, synced: Boolean) = withContext(Dispatchers.IO) {
        markSyncedBlocking(nodeId, synced)
    }

    fun markSyncedBlocking(nodeId: String, synced: Boolean) {
        val current = load()
        if (current.none { it.nodeId == nodeId && it.synced != synced }) return
        commit(current.map { if (it.nodeId == nodeId) it.copy(synced = synced) else it })
    }

    suspend fun clear() = withContext(Dispatchers.IO) { clearBlocking() }

    fun clearBlocking() = commit(emptyList())

    private fun commit(next: List<Peer>) {
        cache = next
        runCatching { settings.savePeersBlocking(next) }
            .onFailure { Log.w("PeerRegistry", "save peers failed", it) }
    }
}

/**
 * 请求鉴权。
 *
 * 三件事，任何一件不过就拒绝：
 *  1. **签名有效** —— 用对端公钥验 `SHA256withRSA`，证明「我是持有这把私钥的节点」；
 *  2. **成员资格** —— 公钥必须在本机名册里。只验签不够：任何人能现生成一对密钥自己签，
 *     那只能证明「我是我」，不能证明「我是这个家的」；
 *  3. **新鲜度** —— 时间戳在 5 分钟窗口内且 nonce 没用过，挡住重放。
 *
 * `Authorization` 用自定义 scheme `Kn1`：git 客户端会把未知 scheme 原样转发，
 * 不会像 Basic 那样被实现强制做 base64，也不像 Bearer 那样容易被中间件改写。
 */
internal class MeshGuard(
    private val peers: PeerRegistry,
    /** @return false 表示这个 nonce 已经用过（重放） */
    private val claimNonce: (String, Long) -> Boolean,
) {
    /**
     * @return 验签通过的 [Peer]，失败返回 null。
     *   这里**不写响应**：git 端点的错误格式和 JSON 端点不同，交给调用方决定。
     */
    fun authorize(call: HttpCall): Peer? {
        val header = call.header("Authorization") ?: return null
        val parts = header.trim().split(' ')
        if (parts.size != 5 || parts[0] != "Kn1") return null
        val nodeId = parts[1]
        val timeMs = parts[2].toLongOrNull() ?: return null
        val nonce = parts[3]
        val sig = parts[4]

        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timeMs) > MeshCrypto.MAX_SKEW_MS) return null

        val peer = peers.find(nodeId) ?: return null
        if (peer.publicKey.isBlank()) return null

        // git 的三个端点共用作用域 "git"：JGit 一次 fetch 会自己连发 info/refs 和
        // upload-pack 两个请求，客户端代码拿不到后者的具体路径，无法逐条签名。
        val isGit = call.path.startsWith("/node.git")
        val scope = if (isGit) "git" else call.path
        if (!MeshCrypto.verify(peer.publicKey, scope, timeMs, nonce, sig)) return null

        // **nonce 的复用粒度是「同一种请求」，不是「整个会话」**。
        //
        // 一开始做成 nonce 全局一次性，结果 git 客户端必然挂：一次 clone 会发
        // `GET info/refs` 和 `POST git-upload-pack` 两个请求，而 JGit 的
        // `setAdditionalHeaders` 是 per-Transport 的静态 map —— 两个请求带的是
        // **同一个 Authorization、同一个 nonce**，于是第二个必定被重放检测拒掉，
        // 表现是 `ls-remote` 能过、`clone` 403。这不是客户端能修的。
        //
        // 折中方案：允许同一 nonce 在同一 (方法, 路径类别) 上重复。这样挡住的正是
        // 真正有意义的攻击 —— 拿抓到的包去**换一个端点和一种操作**；而重复发同一
        // 请求最多只会让服务端再算一遍同一个 pack / 再应用一次同一批提交，
        // 内容是签名之外的，攻击者无法借它替换载荷。
        val pathClass = when {
            !isGit -> call.path
            call.path.endsWith("/info/refs") -> "git-info-refs"
            call.path.endsWith("/git-upload-pack") -> "git-upload-pack"
            call.path.endsWith("/git-receive-pack") -> "git-receive-pack"
            else -> "git-other"
        }
        if (!claimNonce("$nonce|${call.method}|$pathClass", now)) return null

        return peer
    }

    fun noteSeen(peer: Peer, remote: String, port: Int) {
        if (remote.isBlank() || remote == "0.0.0.0") return
        peers.touch(peer.nodeId, "$remote:$port")
    }

    fun addPeer(req: JoinRequest, remote: String, port: Int) {
        // 回环来源（adb forward / 本机自连）拿不到对端真实位置，宁可留空，
        // 让后续扫描或对端主动连入时再补，也不要把 127.0.0.1 写进名册
        val usableRemote = !remote.isBlank() && remote != "0.0.0.0" && !remote.startsWith("127.")
        peers.upsertBlocking(
            Peer(
                nodeId = req.nodeId,
                name = req.name.ifBlank { req.email },
                email = req.email,
                publicKey = req.publicKey,
                address = if (usableRemote) "$remote:${req.port}" else "",
                lastSeenAt = System.currentTimeMillis(),
            ),
        )
        // req.port 是对端自报的；局域网里通常就是统一端口，但对方改过设置时要听它的
        if (usableRemote && req.port in 1..65535 && req.port != port) {
            peers.touch(req.nodeId, "$remote:${req.port}")
        }
    }
}
