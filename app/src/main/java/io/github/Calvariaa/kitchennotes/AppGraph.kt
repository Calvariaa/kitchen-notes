package io.github.Calvariaa.kitchennotes

import android.app.Application
import io.github.Calvariaa.kitchennotes.data.AppSettings
import io.github.Calvariaa.kitchennotes.data.FamilyGroup
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.RecipeRepository
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import io.github.Calvariaa.kitchennotes.sync.GitRepo
import io.github.Calvariaa.kitchennotes.sync.HostRepository
import io.github.Calvariaa.kitchennotes.sync.LanDiscovery
import io.github.Calvariaa.kitchennotes.sync.SshKeyFactory
import io.github.Calvariaa.kitchennotes.sync.SyncClient
import io.github.Calvariaa.kitchennotes.sync.SyncError
import io.github.Calvariaa.kitchennotes.sync.SyncPart
import io.github.Calvariaa.kitchennotes.sync.SyncResult
import io.github.Calvariaa.kitchennotes.update.ApkDownloader
import io.github.Calvariaa.kitchennotes.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手写的服务定位器。
 *
 * 这个 App 只有 5 个屏、6 个服务，引 Hilt/Koin 的收益不抵它带来的构建复杂度
 * （KSP + 注解处理在 AGP 9 上还有额外坑）。全部走 Application 单例 + 手动注入，
 * 编译快、跳转可读、调试时栈干净。
 */
class AppGraph(private val app: Application) {

    /** ViewModel 里会用到 Context，暴露成只读属性 */
    val baseContext: android.content.Context get() = app

    val settings = SettingsStore(app)
    val recipes = RecipeRepository(app)
    val gitRepo = GitRepo(app, recipes)
    val hostRepository = HostRepository(app, settings)
    val lanDiscovery = LanDiscovery(app, settings)
    val syncClient = SyncClient(app, settings, gitRepo, hostRepository)

    /** 应用内更新：查 Releases + 下载 APK。共用一份 OkHttpClient，避免重复建连接池 */
    val updateChecker = UpdateChecker()
    val apkDownloader = ApkDownloader()

    /** 主机服务，只在 HOST 角色时启动 */
    @Volatile
    var gitServer: io.github.Calvariaa.kitchennotes.sync.GitServerHost? = null

    fun identity(): UserIdentity? = settings.identity()

    /**
     * 首次启动或身份缺失时生成身份。SSH 密钥在这里一并生成，
     * 因为「加入家庭组」时要用公钥登记，而密钥生成是纯 CPU 活，趁启动做掉。
     */
    suspend fun ensureIdentity(name: String, email: String): UserIdentity {
        val existing = settings.identity()
        val keepKey = existing != null &&
            existing.sshPrivateKeyPem.isNotBlank() &&
            existing.email == email

        // RSA-2048 生成在低端机上要几十到几百毫秒。这是 suspend 函数，
        // 调用方都在 viewModelScope（主线程）里，所以必须显式切走 ——
        // 在 Android 12 的旧机型上主线程卡超过 5 秒就是 ANR，卡 1 秒也会掉帧。
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
        // 身份先落盘：这一步成功，用户就已经「进来了」，可以正常记菜谱。
        settings.saveIdentity(identity)

        // git 仓库初始化（JGit + 文件系统）是首次启动最容易炸的一步，但它不是
        // 进入 App 的前提 —— 列表读的是 files/store/repo/dishes 下的 JSON，不依赖 .git。
        // 所以这里吞掉失败：真出问题，同步时会再试一次并报可读的错误，
        // 而不是把用户挡在引导页外面还看不见原因。
        runCatching { gitRepo.ensureInitialized(identity) }
            .onFailure { android.util.Log.w("AppGraph", "git init failed during onboarding", it) }

        return identity
    }

    suspend fun sync(): SyncResult {
        val identity = identity() ?: throw SyncError(SyncError.Kind.NO_IDENTITY)
        val group = settings.group() ?: throw SyncError(SyncError.Kind.NO_GROUP)
        return if (group.role == FamilyGroup.Role.HOST) {
            hostSync()
        } else {
            syncClient.sync(identity, member = true)
        }
    }

    /** 主机：先把成员推来的提交拉进工作区，再把本地改动推回裸仓库 */
    suspend fun hostSync(): SyncResult {
        val identity = identity() ?: throw SyncError(SyncError.Kind.NO_IDENTITY)
        val server = gitServer ?: throw SyncError(SyncError.Kind.HOST_NOT_RUNNING)
        server.pullBareIntoWorkspace(identity)
        server.pushWorkspaceToBare(identity)
        return SyncResult(parts = listOf(SyncPart.PUSHED))
    }

    fun localSettings(): AppSettings = settings.get()
}
