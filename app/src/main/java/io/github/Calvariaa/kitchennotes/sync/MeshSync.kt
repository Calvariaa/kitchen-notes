package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import android.util.Log
import io.github.Calvariaa.kitchennotes.data.MeshGroup
import io.github.Calvariaa.kitchennotes.data.Peer
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import java.io.File
import kotlinx.coroutines.sync.withLock

/**
 * P2P 同步：和**每一个**可用的对端做 fetch → merge → push。
 *
 * 和旧的主机模式差别在一句话：以前所有数据必须经过主机，主机不开机就全家停摆；
 * 现在任何两台在线的设备直连就能交换，谁缺席都不影响其他节点之间的一致性。
 *
 * **为什么每台设备既 fetch 又 push**：全网格里没有「只读副本」。
 * 每个人的编辑都在自己机器上先成为提交，必须主动推给对端，否则对方永远看不到。
 * 反过来说，只要有一台设备每次都把自己和所有在线对端对齐，
 * 最终所有设备都收敛到同一棵提交树 —— 这就是 git 的合并语义天然给的。
 *
 * **为什么逐个对端串行**：JGit 对同一个仓库并发 fetch 会争 index/pack 锁，
 * 而家里只有三五台设备，串行的总耗时在秒级，换来的确定性远比并行省的那几百毫秒值。
 */
class MeshSync(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val gitRepo: GitRepo,
    private val meshRepo: MeshRepo,
    private val peers: PeerRegistry,
) {
    private val api = MeshApi(settings)

    /**
     * 和所有 `active` 对端同步一轮。
     *
     * @param identity 本机身份
     * @param group    家庭组（决定推给谁、验谁）
     * @param selfNodeId 本机 nodeId，用于跳过自己（扫描时会扫到自己）
     */
    suspend fun syncAll(
        identity: UserIdentity,
        group: MeshGroup,
        selfNodeId: String,
        silent: Boolean = true,
    ): SyncResult = withContext(Dispatchers.IO) {
        // 整轮同步串行化：commitAll / fetch / merge / push / 镜像裸仓库
        // 全都要碰同一个仓库，和 postReceive 后台回灌、UI 的 commitAndPush 并发
        // 就是 index.lock 抢锁 + 静默失败（坑 38）。锁在整个 syncAll 外层，
        // 而不是每个子步骤各锁一次 —— 否则两个 syncAll 仍会在「我提交到一半」时交错。
        GitLock.mutex.withLock {
            gitRepo.ensureInitialized(identity)
            // 先把本地改动落成提交。不然对端 fetch 到的是上一次提交的状态，
            // 用户刚改的菜谱「同步了但没出去」
            gitRepo.commitAll(identity, "recipe:sync ${System.currentTimeMillis()}")
            meshRepo.ensureBare(identity)
            // pushWorkspaceToBare 可能在镜像前先回灌一次（裸仓库里有工作区还没见过的
            // 提交时必须先合并，否则强制推送会把对端刚送到的数据抹掉）。
            // 那次合并要用本机身份署名，所以在动仓库之前就写进去。
            meshRepo.identitySnapshot = identity
            meshRepo.pushWorkspaceToBare()

            val targets = peers.all().filter { it.nodeId != selfNodeId && it.address.isNotBlank() }
            if (targets.isEmpty()) {
                return@withLock SyncResult(parts = listOf(SyncPart.COMMITTED_LOCAL))
            }

            var pulled = 0
            // 多对端的冲突清单要聚合：一轮同步可能跟三台设备各撞一次，
            // 只留最后一份会让用户漏看前两次被覆盖的内容。按记录 id 去重，
            // 同一份记录跟多个对端撞车时合并字段名。
            val entries = LinkedHashMap<String, ConflictEntry>()
            var unresolved = 0
            var pushedAny = false
            var reachable = 0

            targets.forEach { peer ->
                val outcome = runCatching { syncOne(identity, group, peer) }
                    .onFailure {
                        // 静默同步对外不弹提示，但日志里必须留痕：
                        // 「什么都没发生」在 P2P 里是常态，没有日志就分不清
                        // 「对端确实没数据」和「签名被拒/地址写错」
                        Log.w(TAG, "sync with ${peer.address} failed", it)
                    }
                    .getOrNull()
                if (outcome == null) {
                    // 对端离线是最常见的正常情况（手机关屏、走出 WiFi）。
                    // 静默同步时不该因此打扰用户，也不该中断对其他对端的尝试
                    peers.markSynced(peer.nodeId, false)
                    return@forEach
                }
                reachable++
                pulled += outcome.pulled
                outcome.report.entries.forEach { e ->
                    val key = "${e.kind}/${e.id}"
                    val prev = entries[key]
                    entries[key] = if (prev == null) e else prev.copy(
                        fields = (prev.fields + e.fields).distinct(),
                        autoMerged = (prev.autoMerged + e.autoMerged).distinct(),
                        backupName = prev.backupName ?: e.backupName,
                    )
                }
                unresolved += outcome.report.unresolved
                if (outcome.pushed) pushedAny = true
                peers.markSynced(peer.nodeId, outcome.pulled == 0 && !outcome.pushed)
                // 顺手刷新一次对端名册：它会把自己的地址/公钥和它认识的人一起告诉我，
                // 于是新成员不需要广播（局域网里也没有广播通道），
                // 靠「谁跟谁通过话」就把全网地址慢慢补齐
                runCatching {
                    val refreshed = api.peers(peer, identity, settings.get().serverPort)
                    if (refreshed.isNotEmpty()) peers.mergeRemote(refreshed)
                }
            }

            // 全部对端都够不着 → 当作「只有本地提交」，别报错：家里可能就自己开着
            if (reachable == 0) {
                return@withLock SyncResult(parts = listOf(SyncPart.COMMITTED_LOCAL))
            }

            val report = ConflictReport(entries = entries.values.toList(), unresolved = unresolved)
            val parts = buildList {
                if (pulled > 0) add(SyncPart.PULLED)
                if (report.entries.isNotEmpty() || report.unresolved > 0) add(SyncPart.CONFLICTS)
                if (pushedAny) add(SyncPart.PUSHED)
                if (isEmpty()) add(SyncPart.UP_TO_DATE)
            }
            SyncResult(
                pulledCommits = pulled,
                conflictsResolved = report.entries.size,
                pushedCommits = if (pushedAny) 1 else 0,
                parts = parts,
                conflictReport = report,
            )
        }
    }

    private class Outcome(val pulled: Int, val report: ConflictReport, val pushed: Boolean)

    /**
     * 和单个对端一轮。顺序不能换：
     *  1. fetch —— 先拿到对方的最新，否则合并无从谈起；
     *  2. merge —— 把对方提交并进来（冲突按 updatedAt 仲裁）；
     *  3. push  —— 合并后的树推回去。因为刚合并过，天然是非强制快进。
     */
    private suspend fun syncOne(
        identity: UserIdentity,
        group: MeshGroup,
        peer: Peer,
    ): Outcome? {
        val url = "http://${peer.address}/node.git"
        Git.open(gitRepo.repoDir()).use { git ->
            val branch = git.repository.branch ?: Constants.MASTER
            val before = git.repository.resolve(Constants.HEAD)

            git.fetch()
                .setRemote(url)
                .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/${refPrefix(peer)}/*"))
                .setTimeout(30)
                .setTransportConfigCallback(authCallback(identity, group))
                .call()

            val remoteRev = git.repository.resolve("refs/remotes/${refPrefix(peer)}/$branch")
                ?: return Outcome(0, ConflictReport.NONE, false)

            var report = ConflictReport.NONE
            var pulled = 0
            if (before == null || remoteRev != before) {
                // 先数清楚「对方比我多的提交」，再决定动不动仓库。
                // `pulled` 必须是**真实差异**：旧实现把它算成
                // `addRange(before, remoteRev)`，而当两边靠空合并提交互相追着跑时，
                // 这个数字每轮都不为 0，UI 就永远报「拉取 N 个提交」（坑 39）。
                val aheadOfMe = runCatching {
                    if (before == null) {
                        git.log().add(remoteRev).call().count()
                    } else {
                        git.log().addRange(before, remoteRev).call().count()
                    }
                }.getOrDefault(0)

                val merge = runCatching {
                    git.merge()
                        .include(remoteRev)
                        .setStrategy(MergeStrategy.RECURSIVE)
                        .setFastForward(MergeCommand.FastForwardMode.FF)
                        // 只在需要时生成合并提交；快进时 JGit 自己不会造空提交。
                        // setCommit(true) 是必要的（否则工作区停在 MERGING），
                        // 但配套的 message 用默认的就好 —— 我们不再手工补提交。
                        .setCommit(true)
                        .setMessage("merge: peer ${peer.name.ifBlank { peer.nodeId.take(12) }}")
                        .call()
                }.getOrNull()

                val mergedCleanly = merge != null && merge.mergeStatus.isSuccessful
                if (!mergedCleanly) {
                    // 冲突交给按「字段级三方合并」仲裁。仲裁后**必须**提交收尾，
                    // 否则工作区停在 MERGING，之后所有 commit 都抛
                    // "Cannot commit during a merge"。
                    // 显式传 before / remoteRev：JGit 在 CheckoutConflictException
                    // 路径上不写 MERGE_HEAD，靠它取 base 必然拿不到（坑 42）
                    report = gitRepo.resolveConflictsKeepingNewest(before, remoteRev)
                    // commitAll 自带「有冲突标记就拒绝提交」，所以解不掉的冲突
                    // 不会再被写进提交、也不会推给对端（坑 40）
                    gitRepo.commitAll(identity, "merge: resolve ${report.entries.size} by newest")
                }
                // 真正拉到多少，取决于「合并前对方领先我多少」而不是合并后多了几个提交
                pulled = if (mergedCleanly || report.entries.isNotEmpty()) aheadOfMe else 0
            }

            val head = git.repository.resolve(Constants.HEAD) ?: return Outcome(pulled, report, false)
            var pushed = false
            if (head != remoteRev) {
                val result = git.push()
                    .setRemote(url)
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .setTimeout(30)
                    .setTransportConfigCallback(authCallback(identity, group))
                    .call()
                pushed = result.any { it.remoteUpdates.isNotEmpty() }
            }

            // 推完把本地最新状态镜像到本机裸仓库，
            // 这样第三个节点连我时能直接拿到这次合并的结果
            meshRepo.pushWorkspaceToBare()
            return Outcome(pulled, report, pushed)
        }
    }

    /**
     * 每个对端单独一个 remote 命名空间，避免多对端互相覆盖 `refs/remotes/origin`。
     *
     * **必须过滤掉 base64 里的 `/` 和 `+`**：nodeId 是 `SHA256:` + 标准 base64 指纹，
     * 直接截断会得到 `peer-/Ge9G2QWIJtd` —— `/` 在 git ref 里是路径分隔符，
     * JGit 建这个 ref 时抛 `InvalidRefNameException`，整个 fetch 失败。
     * 症状很有迷惑性：**能连上、能握手，但一到解析 remote ref 就炸**，
     * 而且只有指纹里正好含 `/` 的设备才踩到（约一半概率）。
     */
    private fun refPrefix(peer: Peer): String {
        val sanitized = peer.nodeId
            .removePrefix("SHA256:")
            .filter { it.isLetterOrDigit() }
            .take(16)
        return "peer-" + sanitized.ifBlank { "unknown" }
    }

    /**
     * 给 JGit 的 HTTP 传输挂签名头。
     *
     * `Transport.setAdditionalHeaders` 是 JGit 给「自定义鉴权」留的官方口子：
     * git 客户端会把这个 map 原样加进每个请求（包括 `info/refs` 与后续的
     * `upload-pack`/`receive-pack`），正好覆盖我们需要的三个端点。
     *
     * 用签名而不是 Basic Auth：Basic 的密码要在每次请求都明文（或 base64）发一遍，
     * 而签名带时间戳和 nonce，被录下来也没法重放。
     */
    private fun authCallback(identity: UserIdentity, group: MeshGroup) =
        org.eclipse.jgit.api.TransportConfigCallback { transport: Transport ->
            if (transport is TransportHttp) {
                transport.setAdditionalHeaders(
                    MeshAuth.header(
                        identity = identity,
                        // 作用域固定 "git" 且不带 nonce 缓存：每次 fetch/push 都会
                        // 各建一个新 Transport，重签一次是幂等且最省心的做法
                        scope = "git",
                    ),
                )
            }
        }

    private companion object {
        const val TAG = "MeshSync"
    }
}

/** 对端地址解析：`host:port` → 可用的 base URL */
internal fun peerBaseUrl(address: String, defaultPort: Int): String {
    val host = address.substringBefore(":")
    val port = address.substringAfter(":", defaultPort.toString()).toIntOrNull() ?: defaultPort
    return "http://$host:$port"
}

internal fun peerGitUrl(address: String, defaultPort: Int): String =
    peerBaseUrl(address, defaultPort) + "/node.git"
