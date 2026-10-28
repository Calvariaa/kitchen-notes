package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import android.util.Log
import io.github.Calvariaa.kitchennotes.data.MeshGroup
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 一个对等节点的服务端。
 *
 * **和旧的主机模式最大的区别**：这里没有「主机」。每台设备都跑同一个服务，
 * 都监听同一个端口，都对外提供 `node.git` 和自己的名册。谁在线谁就能被同步，
 * 没有任何一台设备是「必须有」的。
 *
 * 端点：
 *  - `GET  /node.git/info/refs?service=…`    git 广告（需签名）
 *  - `POST /node.git/git-upload-pack`        fetch 数据（需签名）
 *  - `POST /node.git/git-receive-pack`       push 数据（需签名）
 *  - `GET  /mesh/info`                       我是谁 + 我认识谁（需签名）
 *  - `POST /mesh/join`                       请求加入，交出加入码（无需签名，见下）
 *
 * 为什么 `/mesh/join` 不验签：来者还没入组，我们手上没有他的公钥，没法验签。
 * 它自带 `inviteCode`（由家庭密钥派生）作为准入凭证，并在同一请求里交上自己的公钥；
 * 验完码就登记进名册，之后所有请求都走签名。
 */
class MeshServer(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val meshRepo: MeshRepo,
    private val peers: PeerRegistry,
) {
    private var server: MiniHttpServer? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: SyncError? = null
        private set

    /** 对端 push 完成、工作区已合并后回调，让 UI 立刻反映家人改动 */
    @Volatile
    /**
     * 工作区被对端 push 回灌后回调，参数是这次回灌的冲突清单。
     *
     * 之所以不只是「通知刷新一下」：回灌过程同样会做字段级仲裁，
     * **同样可能盖掉本机刚写下的改动**。那条路径发生在后台 daemon 线程里，
     * 不把清单送到 UI，用户就永远不知道自己的修改被对端的旧值换掉了。
     */
    var onPeerChange: ((ConflictReport) -> Unit)? = null

    @Volatile
    private var identity: UserIdentity? = null

    @Volatile
    private var group: MeshGroup? = null

    @Volatile
    private var groupNameOverride: String? = null

    /**
     * 重放防护：5 分钟窗口内见过的 nonce。
     *
     * 过期即淘汰，且有容量上限 —— 节点会被在线设备持续调用，
     * 无界集合就是内存泄漏。清理在每次写入时顺带做（Windows 上没有
     * ScheduledExecutor 的廉价替代，而这里本来就有稳定的调用频率）。
     */
    private val seenNonces = LinkedHashMap<String, Long>(256, 0.75f, true)

    fun deviceName(): String =
        runCatching { android.os.Build.MODEL.takeIf { it.isNotBlank() } }.getOrDefault("Android") ?: "Android"

    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    fun setGroupName(name: String) {
        groupNameOverride = name.trim().takeIf { it.isNotBlank() }
    }

    /** 家庭组变更（创建/加入/改名）后必须重载，否则端点还在用旧 secret 校验 */
    fun updateGroup(group: MeshGroup?) {
        this.group = group
    }

    private fun claimNonce(nonce: String, now: Long): Boolean = synchronized(seenNonces) {
        val cutoff = now - MeshCrypto.MAX_SKEW_MS
        val it = seenNonces.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove() else break
        }
        if (seenNonces.containsKey(nonce)) return false
        seenNonces[nonce] = now
        true
    }

    suspend fun start(identity: UserIdentity, group: MeshGroup) = withContext(Dispatchers.IO) {
        this@MeshServer.identity = identity
        this@MeshServer.group = group
        if (running) return@withContext

        val port = settings.get().serverPort
        // 裸仓库准备和端口监听分开 try：两者失败的原因完全不同，
        // 混在一个 catch 里会把「JGit 建仓库炸了」报成「端口被占用」，
        // 用户照着提示去改端口，永远也修不好。
        // 持 GitLock 后再动仓库：本方法可能在任意时刻被调用（建组/加入/开机），
        // 而此刻 postReceive 回灌或定时同步可能正握着仓库（坑 38）。
        val repoError = runCatching {
            GitLock.mutex.withLock { meshRepo.ensureBare(identity) }
        }.exceptionOrNull()
        if (repoError != null) {
            Log.w(TAG, "prepare bare repo failed", repoError)
            lastError = SyncError(
                SyncError.Kind.REPO_UNAVAILABLE,
                "${repoError.javaClass.simpleName}: ${repoError.message ?: ""}",
            )
            running = false
            return@withContext
        }
        try {
            val http = MiniHttpServer(port)
            val guard = MeshGuard(peers, ::claimNonce)

            val gitHandler = MiniGitHandler(meshRepo, this@MeshServer)
            gitHandler.currentIdentity = identity
            gitHandler.onWorkspacePulled = { report -> onPeerChange?.invoke(report) }

            http.route("/node.git") { call ->
                val peer = guard.authorize(call)
                if (peer == null) {
                    runCatching { call.respondText(403, "forbidden") }
                    return@route
                }
                guard.noteSeen(peer, call.remote, port)
                try {
                    gitHandler.handle(call)
                } catch (t: Throwable) {
                    Log.w(TAG, "git request failed", t)
                    runCatching { call.respondText(500, "git failed") }
                }
            }

            http.route("/mesh/info") { call ->
                val peer = guard.authorize(call)
                if (peer == null) {
                    runCatching { call.respondText(403, "forbidden") }
                    return@route
                }
                // 对端主动问候 → 顺手记住它的地址。双向发现靠的就是这一句
                guard.noteSeen(peer, call.remote, port)
                val me = identity
                val g = group
                if (me == null || g == null) {
                    runCatching { call.respondText(503, "not ready") }
                    return@route
                }
                val body = MeshWire.infoBody(
                    identity = me,
                    nodeId = MeshCrypto.nodeId(me.sshPublicKey),
                    group = g,
                    port = port,
                    peers = peers.all(),
                    recipeCount = countRecipes(),
                    appVersion = appVersion(),
                )
                runCatching { call.respondJson(200, body) }
            }

    // 唯一无需签名的端点：凭加入码准入
            http.route("/mesh/join") { call ->
                try {
                    handleJoin(call, guard, port)
                } catch (t: Throwable) {
                    Log.w(TAG, "join failed", t)
                    runCatching { call.respondJson(500, """{"error":"internal"}""") }
                }
            }

            /**
             * 公开探针：给局域网扫描用。
             *
             * 扫描方这时还不知道对端公钥，没法验签 —— 但总得先知道「这个 IP 是不是
             * 一台 kitchen-notes 节点」才能决定要不要继续。所以这个端点回最少的
             * 可识别信息：是不是这个 App、组名、节点名。
             *
             * **刻意不返回**：`secret`、`groupTag`、成员列表、公钥。
             * 否则同一个 WiFi 下任何人扫一遍就能拿到全家公钥去做社工。
             * 组名和节点名本来就是家里人在同一张桌子上互相看得见的东西。
             */
            http.route("/mesh/hello") { call ->
                val g = group
                val body = buildString {
                    append("""{"app":"kitchen-notes","version":2""")
                    append(""","nodeId":""").append(jsonStr(MeshCrypto.nodeId(identity?.sshPublicKey.orEmpty())))
                    append(""","name":""").append(jsonStr(deviceName()))
                    append(""","groupName":""").append(jsonStr(groupNameOverride ?: g?.name ?: ""))
                    append(""","hasGroup":""").append(g?.isConfigured == true)
                    append(""","port":""").append(port)
                    append("}")
                }
                runCatching { call.respondJson(200, body) }
            }

            http.start()
            server = http
            running = true
            lastError = null
            Log.i(TAG, "mesh node listening on $port")
        } catch (t: Throwable) {
            // 端口占用最常见。绝不能让异常逃到 viewModelScope —— 那会整个进程崩掉（坑 15）
            Log.w(TAG, "start failed", t)
            lastError = if (t is java.net.BindException) {
                // BindException 在 Android 上 message 常常是 null（libcore 的
                // IoBridge 只填 errno，不填文案），直接拼出来就是「端口 7411
                // 被占用：」后面空一截，用户没法照做。所以按 errno 兜一句话。
                SyncError(SyncError.Kind.PORT_UNAVAILABLE, "$port", bindHint(t))
            } else {
                SyncError(
                    SyncError.Kind.PORT_UNAVAILABLE,
                    "$port",
                    "${t.javaClass.simpleName}: ${t.message ?: bindHint(t)}",
                )
            }
            runCatching { server?.stop() }
            server = null
            running = false
        }
    }

    /**
     * `BindException` 在 Android 上 `message` 经常是 null：libcore 的 `IoBridge.bind`
     * 只把 errno 抛上来，不填文案。所以直接 `t.message` 拼出来的界面是
     * 「端口 7411 被占用：」后面空空一截，用户没法照做。
     * 这里按 errno 兜一句能直接行动的话。
     */
    private fun bindHint(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("EADDRINUSE", ignoreCase = true) ->
                "EADDRINUSE — another app (or a previous instance of this one) is already on this port"
            msg.contains("EACCES", ignoreCase = true) ->
                "EACCES — the system refused this port; pick a different one"
            msg.isNotBlank() -> msg
            else -> t.javaClass.simpleName
        }
    }

    private fun handleJoin(call: HttpCall, guard: MeshGuard, port: Int) {
        val req = MeshWire.decodeJoinRequest(call.bodyText())
        val g = group
        if (req == null || req.publicKey.isBlank() || req.nodeId.isBlank()) {
            call.respondJson(400, """{"error":"missingFields"}""")
            return
        }
        if (g == null || !g.isConfigured) {
            // 对方找错了节点：这台还没建家庭
            call.respondJson(409, """{"error":"noGroup"}""")
            return
        }
        // 自报 nodeId 必须和公钥指纹一致，否则可以拿别人的 id 冒充
        if (MeshCrypto.nodeId(req.publicKey) != req.nodeId) {
            call.respondJson(400, """{"error":"badIdentity"}""")
            return
        }
        if (!MeshCrypto.inviteMatches(g.secret, req.inviteCode)) {
            call.respondJson(403, """{"error":"token"}""")
            return
        }

        // 把 secret 用**加入者公钥**封好回给他。中间人截到这个响应也解不开，
        // 而他自己没有 secret 能算加入码 —— 双向都不需要明文过网
        val sealed = MeshCrypto.encryptForPeer(req.publicKey, g.secret)
        if (sealed == null) {
            call.respondJson(400, """{"error":"badPublicKey"}""")
            return
        }

        guard.addPeer(req, call.remote, port)
        val me = identity
        if (me == null) {
            call.respondJson(503, """{"error":"not ready"}""")
            return
        }
        call.respondJson(
            200,
            MeshWire.joinResponseBody(
                groupName = g.name,
                sealedSecret = sealed,
                // 必须把自己的公钥一起给他：它名册里不会有「我」，
                // 而之后每次请求都要用我的公钥验签
                self = me,
                selfNodeId = MeshCrypto.nodeId(me.sshPublicKey),
                port = port,
                peers = peers.all(),
            ),
        )
        Log.i(TAG, "peer joined: ${req.nodeId} (${req.name})")
    }

    private fun countRecipes(): Int = runCatching {
        Paths.dishesDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
    }.getOrDefault(0)

    private fun appVersion(): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { server?.stop() }
        server = null
        running = false
    }

    /** 当前对外地址列表（`ip:port`），UI 展示用 */
    fun addresses(): List<String> {
        val port = settings.get().serverPort
        return localAddresses().map { "$it:$port" }
    }

    private companion object {
        const val TAG = "MeshServer"

        /** 最小 JSON 转义：这里只拼自己产生的短字符串，不需要完整实现 */
        fun jsonStr(s: String): String = buildString {
            append('"')
            s.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
