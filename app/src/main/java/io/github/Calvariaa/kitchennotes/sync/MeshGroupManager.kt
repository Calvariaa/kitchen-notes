package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import android.util.Log
import io.github.Calvariaa.kitchennotes.data.MeshGroup
import io.github.Calvariaa.kitchennotes.data.Peer
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 家庭组的创建、加入、离开 —— 用户动作的编排层。
 *
 * 单独抽出来是因为这三件事都涉及「多步且必须按序」：
 * 创建要先有 secret 才能派生加入码；加入要先拿到 secret 才能验签名；
 * 离开要清名册但保留本地数据。把这些散在 ViewModel 里会让那个已经
 * 上千行的类更难读，也会让「同步层」和「UI 层」互相泄漏概念。
 */
class MeshGroupManager(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val peers: PeerRegistry,
    private val server: MeshServer,
    private val meshSync: MeshSync,
    private val meshRepo: MeshRepo,
    private val gitRepo: GitRepo,
) {
    /** 创建家庭：本机生成 secret，从此这台设备就是「第一个节点」 */
    suspend fun create(identity: UserIdentity, name: String): MeshGroup =
        withContext(Dispatchers.IO) {
            val group = MeshGroup(
                name = name.trim().ifBlank { "Kitchen" },
                secret = MeshCrypto.newSecret(),
                joinedAt = System.currentTimeMillis(),
            )
            settings.saveGroup(group)
            peers.clear()
            // 把自己也登记进名册：UI 上「家庭成员」列表要能显示自己，
            // 而且同步循环遍历 peers 时要能识别出「这是我」而跳过
            server.start(identity, group)
            server.updateGroup(group)
            // 建裸仓库失败就让 create 失败，但必须抛带原因的类型化错误：
            // 让裸异常冒到 ViewModel 的话，那里只能笼统说一句「节点启动失败」，
            // 用户和我们都看不出是 JGit 出了问题还是端口出了问题。
            // 持 GitLock：建仓库 / 提交 / 镜像三步之间不能和别的同步交错（坑 38）
            GitLock.mutex.withLock {
                runCatching { meshRepo.ensureBare(identity) }
                    .onFailure { throw SyncError(SyncError.Kind.REPO_UNAVAILABLE, describe(it)) }
                // 先在工作区落一个提交，再镜像过去。顺序反了的话工作区可能还是
                // unborn HEAD，push 会因为「源 ref 解析不到对象」直接失败，
                // 于是裸仓库永远停在空提交上 —— 家人加入后拉到的是空仓库。
                runCatching { gitRepo.commitAll(identity, "init: first node") }
                runCatching { meshRepo.pushWorkspaceToBare() }
                    .onFailure { throw SyncError(SyncError.Kind.REPO_UNAVAILABLE, describe(it)) }
            }
            group
        }

    /** 把异常压成一行技术细节：类名 + 消息，给用户看的部分由资源文案承担 */
    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"

    /** 加入家庭：扫到对端 → 用加入码换 secret → 拉名册 → 对齐数据 */
    suspend fun join(
        identity: UserIdentity,
        target: DiscoveredNode,
        inviteCode: String,
    ): JoinOutcomeFull = withContext(Dispatchers.IO) {
        val port = settings.get().serverPort
        val outcome = MeshApi(settings).join(target, inviteCode, identity, port)

        settings.saveGroup(outcome.group)

        // 名册里对端自己那条由 MeshApi 从 JoinResponse.self* 补齐（带公钥），
        // 这里只做「去掉自己」这一步 —— 扫描时自己也会被扫到
        val selfId = MeshCrypto.nodeId(identity.sshPublicKey)
        peers.mergeRemote(outcome.peers.filter { it.nodeId != selfId })

        // 启动服务端：加入之后本机也是完整节点，别人也能连我
        server.start(identity, outcome.group)
        server.updateGroup(outcome.group)
        // 持 GitLock：紧跟着的 syncAll 也会要这把锁，这里先做完提交+镜像
        GitLock.mutex.withLock {
            meshRepo.ensureBare(identity)
            // 先把自己已有的菜谱落成提交并镜像出去，对端才能拉到「我这边有什么」，
            // 否则加入后的第一轮同步只能单向拿到对方的，我的菜谱要等下次编辑才出现
            runCatching { gitRepo.commitAll(identity, "init: joined mesh") }
            meshRepo.pushWorkspaceToBare()
        }

        // 立刻同步一轮：把对端数据拉下来，也让对端知道我在
        val result = runCatching {
            meshSync.syncAll(identity, outcome.group, selfId, silent = false)
        }.getOrNull()

        JoinOutcomeFull(outcome.group, peers.all(), result ?: SyncResult())
    }

    /** 改名。secret 不变，所以加入码不变，其他节点不用重新加入 */
    suspend fun rename(name: String): MeshGroup? = withContext(Dispatchers.IO) {
        val current = settings.group() ?: return@withContext null
        val updated = current.copy(name = name.trim().ifBlank { current.name })
        settings.saveGroup(updated)
        server.updateGroup(updated)
        updated
    }

    /**
     * 离开家庭。
     *
     * **不删本地数据**：菜谱还在，只是不再和别人同步。
     * 删了才是灾难 —— 用户可能只是想换个组，或者误触。
     * 名册和 secret 清掉，本地 git 历史留着（那是用户自己的记录）。
     */
    suspend fun leave() = withContext(Dispatchers.IO) {
        runCatching { server.stop() }
        settings.saveGroup(MeshGroup(name = "", secret = ""))
        settings.clearGroup()
        peers.clear()
        Log.i("MeshGroupManager", "left group, local data kept")
    }

    class JoinOutcomeFull(
        val group: MeshGroup,
        val peers: List<Peer>,
        val sync: SyncResult,
    )
}
