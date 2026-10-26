package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import android.util.Log
import io.github.Calvariaa.kitchennotes.data.FamilyGroup
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.RecipeRepository
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.http.server.GitServlet
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * 主机模式：在本机跑一个小型 HTTP 服务，同时提供
 *   /grocery.git/...   git smart HTTP（pull/push）
 *   /api/info          局域网发现探针
 *   /api/join          成员用令牌 + 公钥登记
 *   /api/leave         退出
 *
 * 用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 而不是 Ktor/Netty：
 * 只需要几个路由，自带 HttpServer 零依赖、体积几十 KB，对 APK 体积敏感的场景是正解。
 * GitServlet 需要 Servlet API，这里用一个极简适配层把 HttpExchange 桥接到 Servlet 接口。
 */
class GitServerHost(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val hostRepo: HostRepository,
    private val recipeRepo: RecipeRepository,
    private val gitRepo: GitRepo,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: com.sun.net.httpserver.HttpServer? = null
    private var gitRepository: Repository? = null

    @Volatile
    var running: Boolean = false
        private set

    /**
     * 启动失败的原因。存结构化错误而不是句子 —— 端口占用这类错误要按当前界面语言展示，
     * 而服务端在这里拿不到 UI 语言。
     */
    @Volatile
    var lastError: SyncError? = null
        private set

    /** 主机名（家庭组显示用），由 UI 在启动前设置 */
    @Volatile
    private var groupNameOverride: String? = null

    /**
     * 成员 push 完成后、工作区已更新时的回调。由 ViewModel 注入，
     * 用来在主机 UI 上立刻反映出家人的改动（而不是等下一次手动刷新）。
     */
    @Volatile
    var onRemoteChange: (() -> Unit)? = null

    fun setGroupName(name: String) {
        groupNameOverride = name.trim().takeIf { it.isNotBlank() }
    }

    fun deviceName(): String =
        runCatching { android.os.Build.MODEL.takeIf { it.isNotBlank() } }.getOrDefault("Android") ?: "Android"

    /** 主机在所有网卡上的 IPv4 地址，家庭组二维码/地址列表用 */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    suspend fun start(identity: UserIdentity) = withContext(Dispatchers.IO) {
        if (running) return@withContext
        val port = settings.get().serverPort

        // 组名：UI 传入优先，否则沿用上次的
        val existingMeta = runCatching { hostRepo.readMeta() }.getOrNull()
        val meta = (existingMeta ?: HostRepository.GroupMeta(
            HostRepository.FALLBACK_GROUP_NAME, deviceName(), hostRepo.randomToken(),
        )).let {
            groupNameOverride?.let { n -> it.copy(groupName = n) } ?: it
        }
        hostRepo.writeMeta(meta)
        hostRepo.ensureSelfRegistered(identity)

        // 裸仓库：成员推到这里
        val bare = hostRepo.bareDir()
        if (!bare.exists()) {
            Git.init().setBare(true).setDirectory(bare).call().use { }
        }
        val repo = FileRepositoryBuilder().setGitDir(bare).setBare().build()
        repo.config.apply {
            setBoolean("core", null, "fileMode", false)
            setBoolean("http", null, "receivepack", true)
            setBoolean("http", null, "uploadpack", true)
            save()
        }
        // 让默认分支存在，否则客户端第一次 push 会因为没有 ref 而异常
        if (repo.resolve(Constants.HEAD) == null) {
            val branch = Constants.MASTER
            val ident = PersonIdent(identity.name.ifBlank { "dish" }, identity.email.ifBlank { "dish@localhost" })

            val emptyTree = repo.newObjectInserter().use { ins ->
                val treeId = ins.insert(org.eclipse.jgit.lib.TreeFormatter())
                ins.flush()
                treeId
            }
            val commitId = repo.newObjectInserter().use { ins ->
                val builder = org.eclipse.jgit.lib.CommitBuilder().apply {
                    setTreeId(emptyTree)
                    setAuthor(ident)
                    setCommitter(ident)
                    setMessage("init: family group repository")
                }
                val id = ins.insert(builder)
                ins.flush()
                id
            }
            val refUpdate = repo.updateRef("refs/heads/$branch")
            refUpdate.setNewObjectId(commitId)
            refUpdate.forceUpdate()
            repo.updateRef(Constants.HEAD).link("refs/heads/$branch")
        }
        gitRepository = repo

        val httpServer = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress(port),
            0,
        ).apply {
            executor = Executors.newFixedThreadPool(4)
        }

        // ---- git smart HTTP ----
        val gitServlet = MiniGitHandler(repo, this@GitServerHost)
        gitServlet.currentIdentity = identity
        // 成员推完，主机工作区已更新，通知 UI 刷新
        gitServlet.onWorkspacePulled = { onRemoteChange?.invoke() }

        httpServer.createContext("/grocery.git") { exchange ->
            try {
                gitServlet.handle(exchange)
            } catch (t: Throwable) {
                Log.w("GitServerHost", "git request failed", t)
                runCatching {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.close()
                }
            }
        }

        // ---- 发现探针 ----
        httpServer.createContext("/api/info") { exchange ->
            val meta = hostRepo.readMeta()
            val body = buildJsonObject {
                put("groupName", meta.groupName)
                put("hostName", deviceName())
                put("memberCount", meta.members.size)
                put("requireToken", true)
                put("app", "kitchen-notes")
                put("version", 1)
            }.toString()
            respondJson(exchange, 200, body)
        }

        // ---- 加入 ----
        httpServer.createContext("/api/join") { exchange ->
            val token = exchange.requestHeaders.getFirst("X-Dish-Token").orEmpty()
            val meta = hostRepo.readMeta()
            if (token != meta.token) {
                // 线上协议里只放机器码，展示文案由客户端按自己的语言翻译
                respondJson(exchange, 403, """{"error":"token"}""")
                return@createContext
            }
            val form = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val params = parseForm(form)
            val email = params["email"].orEmpty()
            val name = params["name"].orEmpty()
            val pub = params["publicKey"].orEmpty()
            if (email.isBlank() || pub.isBlank()) {
                respondJson(exchange, 400, """{"error":"missingFields"}""")
                return@createContext
            }
            hostRepo.addMember(
                HostRepository.Member(name = name, email = email, publicKey = pub, joinedAt = System.currentTimeMillis()),
            )
            // 把已登记的公钥渲染进授权文件，SSH 通道下次连接即生效
            hostRepo.writeMeta(hostRepo.readMeta())
            // 成员加入后把主机现有数据推一份进裸仓库，避免新成员拉到空仓库
            Thread {
                runCatching { kotlinx.coroutines.runBlocking { pushWorkspaceToBare(identity) } }
            }.also { it.isDaemon = true }.start()
            val body = buildJsonObject {
                put("groupName", meta.groupName)
                put("hostName", deviceName())
                put("port", settings.get().serverPort)
            }.toString()
            respondJson(exchange, 200, body)
        }

        httpServer.createContext("/api/leave") { exchange ->
            val token = exchange.requestHeaders.getFirst("X-Dish-Token").orEmpty()
            val meta = hostRepo.readMeta()
            if (token != meta.token) {
                respondJson(exchange, 403, """{"error":"token"}""")
                return@createContext
            }
            val params = parseForm(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            params["email"]?.let(hostRepo::removeMember)
            respondJson(exchange, 200, """{"ok":true}""")
        }

        try {
            httpServer.start()
            server = httpServer
            running = true
            lastError = null
        } catch (t: Throwable) {
            lastError = SyncError(SyncError.Kind.PORT_UNAVAILABLE, "$port|${t.message}")
            runCatching { repo.close() }
            gitRepository = null
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { server?.stop(0) }
        server = null
        runCatching { gitRepository?.close() }
        gitRepository = null
        running = false
    }

    /** 把主机工作区的提交推到裸仓库，供成员拉取 */
    suspend fun pushWorkspaceToBare(identity: UserIdentity) = withContext(Dispatchers.IO) {
        val repo = gitRepository ?: return@withContext
        val bareUrl = hostRepo.bareDir().absolutePath
        Git.open(gitRepo.repoDir()).use { git ->
            val branch = git.repository.branch ?: Constants.MASTER
            runCatching {
                git.push()
                    .setRemote(bareUrl)
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .call()
            }
        }
    }

    /** 把裸仓库的成员提交拉回主机工作区 */
    suspend fun pullBareIntoWorkspace(identity: UserIdentity) = withContext(Dispatchers.IO) {
        if (gitRepository == null) return@withContext
        Git.open(gitRepo.repoDir()).use { git ->
            val branch = git.repository.branch ?: Constants.MASTER
            val url = hostRepo.bareDir().absolutePath
            runCatching {
                git.pull()
                    .setRemote(url)
                    .setRemoteBranchName(branch)
                    .call()
            }.getOrElse {
                // 非快进（成员改了同一文件）时退化为 fetch + 取新仲裁
                runCatching {
                    git.fetch().setRemote(url).setRefSpecs(
                        RefSpec("+refs/heads/*:refs/remotes/bare/*"),
                    ).call()
                    val target = git.repository.resolve("refs/remotes/bare/$branch")
                    if (target != null) {
                        git.merge().include(target).setMessage("merge: members").call()
                        gitRepo.resolveConflictsKeepingNewest()
                        git.add().addFilepattern(".").call()
                        git.commit().setMessage("merge: resolve by newest").setAllowEmpty(true).call()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ HTTP 小工具

    private fun respondJson(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val v = pair.substringAfter('=', "")
            decode(k) to decode(v)
        }.toMap()

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
