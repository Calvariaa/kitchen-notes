package io.github.Calvariaa.kitchennotes

import android.app.Application
import io.github.Calvariaa.kitchennotes.data.AppSettings
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.Peer
import io.github.Calvariaa.kitchennotes.data.RecipeRepository
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import io.github.Calvariaa.kitchennotes.sync.GitRepo
import io.github.Calvariaa.kitchennotes.sync.LanDiscovery
import io.github.Calvariaa.kitchennotes.sync.MeshCrypto
import io.github.Calvariaa.kitchennotes.sync.MeshGroupManager
import io.github.Calvariaa.kitchennotes.sync.MeshRepo
import io.github.Calvariaa.kitchennotes.sync.MeshServer
import io.github.Calvariaa.kitchennotes.sync.MeshSync
import io.github.Calvariaa.kitchennotes.sync.PeerRegistry
import io.github.Calvariaa.kitchennotes.sync.SshKeyFactory
import io.github.Calvariaa.kitchennotes.sync.SyncError
import io.github.Calvariaa.kitchennotes.sync.SyncResult
import io.github.Calvariaa.kitchennotes.update.ApkDownloader
import io.github.Calvariaa.kitchennotes.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手写的服务定位器。
 *
 * 这个 App 只有 5 个屏、七八个服务，引 Hilt/Koin 的收益不抵它带来的构建复杂度
 * （KSP + 注解处理在 AGP 9 上还有额外坑）。全部走 Application 单例 + 手动注入，
 * 编译快、跳转可读、调试时栈干净。
 */
class AppGraph(private val app: Application) {

    val baseContext: android.content.Context get() = app

    val settings = SettingsStore(app)
    val recipes = RecipeRepository(app)

    /** 菜单（一顿饭的记录，含打分）。和菜谱一样落在 repo/ 下，跟着 git 同步 */
    val menus = io.github.Calvariaa.kitchennotes.data.MenuRepository(app)

    val gitRepo = GitRepo(app, recipes)
    val meshRepo = MeshRepo(app, gitRepo)
    val peerRegistry = PeerRegistry(settings)

    /** 每台设备都跑这个服务端，没有「只有主机才有」的特例 */
    val meshServer = MeshServer(app, settings, meshRepo, peerRegistry)

    val meshSync = MeshSync(app, settings, gitRepo, meshRepo, peerRegistry)
    val groupManager = MeshGroupManager(app, settings, peerRegistry, meshServer, meshSync, meshRepo, gitRepo)
    val lanDiscovery = LanDiscovery(app, settings)

    val updateChecker = UpdateChecker()
    val apkDownloader = ApkDownloader()

    fun identity(): UserIdentity? = settings.identity()

    /** 本机 nodeId（SSH 公钥指纹）。没有身份时返回空串 */
    fun selfNodeId(): String = identity()?.let { MeshCrypto.nodeId(it.sshPublicKey) }.orEmpty()

    /**
     * 首次启动或身份缺失时生成身份。SSH 密钥在这里一并生成，
     * 因为它是节点身份证，而密钥生成是纯 CPU 活，趁启动做掉。
     */
    suspend fun ensureIdentity(name: String, email: String): UserIdentity {
        val existing = settings.identity()
        val keepKey = existing != null &&
            existing.sshPrivateKeyPem.isNotBlank() &&
            existing.email == email

        // RSA-2048 生成在低端机上要几十到几百毫秒。这是 suspend 函数，
        // 调用方都在 viewModelScope（主线程），必须显式切走 ——
        // 主线程卡 5 秒就是 ANR，卡 1 秒也掉帧。
        val (priv, pub) = if (keepKey) {
            existing!!.sshPrivateKeyPem to existing.sshPublicKey
        } else {
            withContext(Dispatchers.Default) {
                SshKeyFactory.generateKeyPair(email.ifBlank { "dish@localhost" })
            }
        }

        val identity = UserIdentity(
            name = name.ifBlank { "厨友" },
            email = email.ifBlank { "dish@local" },
            sshPrivateKeyPem = priv,
            sshPublicKey = pub,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        // 身份先落盘：这一步成功，用户就已经「进来了」，可以正常记菜谱
        settings.saveIdentity(identity)

        // git 仓库初始化（JGit + 文件系统）是首次启动最容易炸的一步，但它不是
        // 进入 App 的前提 —— 列表读的是 repo/dishes 下的 JSON，不依赖 .git。
        // 所以吞掉失败：真出问题，同步时会再试并报可读错误，
        // 而不是把用户挡在引导页外面还看不见原因。
        runCatching { gitRepo.ensureInitialized(identity) }
            .onFailure { android.util.Log.w("AppGraph", "git init failed during onboarding", it) }

        return identity
    }

    /** 一次同步：和所有在线对端交换数据 */
    suspend fun sync(silent: Boolean = true): SyncResult {
        val identity = identity() ?: throw SyncError(SyncError.Kind.NO_IDENTITY)
        val group = settings.group() ?: throw SyncError(SyncError.Kind.NO_GROUP)
        return meshSync.syncAll(identity, group, selfNodeId(), silent)
    }

    /** 创建家庭后立刻把本地已有菜谱变成可被拉取的提交 */
    suspend fun seedIfEmpty(identity: UserIdentity) = withContext(Dispatchers.IO) {
        runCatching {
            gitRepo.commitAll(identity, "init: first node")
            meshRepo.pushWorkspaceToBare()
        }
    }

    fun peers(): List<Peer> = peerRegistry.all()

    fun localSettings(): AppSettings = settings.get()
}
