package com.dishplanner

import android.app.Application
import com.dishplanner.data.AppSettings
import com.dishplanner.data.FamilyGroup
import com.dishplanner.data.Paths
import com.dishplanner.data.RecipeRepository
import com.dishplanner.data.SettingsStore
import com.dishplanner.data.UserIdentity
import com.dishplanner.sync.GitRepo
import com.dishplanner.sync.HostRepository
import com.dishplanner.sync.LanDiscovery
import com.dishplanner.sync.SshKeyFactory
import com.dishplanner.sync.SyncClient
import com.dishplanner.sync.SyncResult

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

    /** 主机服务，只在 HOST 角色时启动 */
    @Volatile
    var gitServer: com.dishplanner.sync.GitServerHost? = null

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

        val (priv, pub) = if (keepKey) {
            existing!!.sshPrivateKeyPem to existing.sshPublicKey
        } else {
            SshKeyFactory.generateKeyPair(email.ifBlank { "dish@localhost" })
        }

        val identity = UserIdentity(
            name = name.ifBlank { "厨友" },
            email = email.ifBlank { "dish@local" },
            sshPrivateKeyPem = priv,
            sshPublicKey = pub,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        settings.saveIdentity(identity)
        gitRepo.ensureInitialized(identity)
        return identity
    }

    suspend fun sync(): SyncResult {
        val identity = identity() ?: return SyncResult(message = "还没设置身份")
        val group = settings.group() ?: return SyncResult(message = "还没加入家庭组")
        return if (group.role == FamilyGroup.Role.HOST) {
            hostSync()
        } else {
            syncClient.sync(identity, member = true)
        }
    }

    /** 主机：先把成员推来的提交拉进工作区，再把本地改动推回裸仓库 */
    suspend fun hostSync(): SyncResult {
        val identity = identity() ?: return SyncResult(message = "还没设置身份")
        val server = gitServer ?: return SyncResult(message = "主机服务没启动")
        server.pullBareIntoWorkspace(identity)
        server.pushWorkspaceToBare(identity)
        return SyncResult(message = "已与家庭成员双向同步")
    }

    fun localSettings(): AppSettings = settings.get()
}
