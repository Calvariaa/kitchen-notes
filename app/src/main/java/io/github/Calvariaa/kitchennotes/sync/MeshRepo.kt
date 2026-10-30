package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import android.util.Log
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import java.io.File

/**
 * 本机对外的裸仓库（`node.git`）生命周期。
 *
 * **为什么每台设备都要一个裸仓库**：P2P 网格里，别人 fetch 的对象必须来自
 * 「不在使用时被改动的」仓库。工作区仓库（`repo/`）随时会被 UI 读写、被 commit
 * 改写 index，让对端直接读它等于把内部状态暴露给并发访问 —— JGit 会抛
 * 各种 pack/index 锁异常，而且对端拿到的是「用户刚好在编辑的那一瞬」的树。
 *
 * 裸仓库是只进不出的镜像：本地 commit 完推给它，它只被 git 协议读取。
 * 代价是多一次本地 push（无网络、纯文件拷贝，几十毫秒），换来的是并发安全。
 *
 * 工作区与裸仓库之间的两个方向：
 *  - `pushWorkspaceToBare`：本地编辑后，把工作区提交同步过去（别人才能拉到）
 *  - `pullBareIntoWorkspace`：对端 push 进来后，把裸仓库的新提交合并回工作区（UI 才能看到）
 */
class MeshRepo(private val ctx: Context, private val gitRepo: GitRepo) {

    fun bareDir(): File = Paths.bare(ctx)

    /** 打开本机裸仓库（必要时创建）。每个请求都要用，所以不缓存 Repository 实例 ——
     *  JGit 的 Repository 不是线程安全的，缓存反而要外加锁；开一个只是一次文件读取 */
    fun openBare(): Repository = FileRepositoryBuilder().setGitDir(bareDir()).setBare().build()

    /**
     * 确保裸仓库存在且有初始提交。
     *
     * 初始提交不能省：没有任何 ref 的裸仓库，客户端 `git clone` 会拿到
     * 「warning: You appear to have cloned an empty repository」，紧接着
     * 我们的 `adopt` 逻辑要处理空远端的分支，多一条特殊路径。
     * 垫一个空树提交，拓扑上就永远有共同祖先，合并逻辑只剩一条路。
     */
    suspend fun ensureBare(identity: UserIdentity) = withContext(Dispatchers.IO) {
        migrateLegacyBare()
        val bare = bareDir()
        // 不能用 `if (!bare.exists())` 判断「仓库是否可用」：迁移过来的目录名存在，
        // 但内部 objects/ 可能不完整（旧主机模式从没往里写过对象、或只建了个空壳），
        // 此时 JGit 会在写临时对象时抛 `IOException: No such file or directory`，
        // 而且是**在 TreeFormatter.insert 里**炸，栈里完全看不到跟目录有关的信息。
        // 所以以「objects 目录是否存在」为判定哨兵，缺了就让 Git.init 补齐骨架。
        if (!bare.exists() || !File(bare, "objects").isDirectory) {
            Git.init().setBare(true).setDirectory(bare).call().use { }
        }
        openBare().use { repo ->
            repo.config.apply {
                setBoolean("core", null, "fileMode", false)
                // 本机自己会往里 push（工作区 → 裸仓库），日常 rebase 即可，不跑 gc
                setInt("gc", null, "auto", 0)
                save()
            }
            // resolve(HEAD) 在 HEAD 指向的分支不存在时会抛异常而不是返回 null
            // （旧仓库迁移过来很常见），所以外层套 runCatching
            val head = runCatching { repo.resolve(Constants.HEAD) }.getOrNull()
            if (head == null) {
                val ident = PersonIdent(
                    identity.name.ifBlank { "dish" },
                    identity.email.ifBlank { "dish@localhost" },
                )
                val emptyTree = repo.newObjectInserter().use { ins ->
                    val id = ins.insert(org.eclipse.jgit.lib.TreeFormatter())
                    ins.flush()
                    id
                }
                val commitId = repo.newObjectInserter().use { ins ->
                    val id = ins.insert(
                        org.eclipse.jgit.lib.CommitBuilder().apply {
                            setTreeId(emptyTree)
                            setAuthor(ident)
                            setCommitter(ident)
                            setMessage("init: mesh node repository")
                        },
                    )
                    ins.flush()
                    id
                }
                repo.updateRef("refs/heads/${Constants.MASTER}").apply {
                    setNewObjectId(commitId)
                    forceUpdate()
                }
                repo.updateRef(Constants.HEAD).link("refs/heads/${Constants.MASTER}")
            }
        }
    }

    /**
     * 把主机模式遗留的 `grocery.git` 改名成 `node.git`。
     *
     * **只有真的是 git 裸仓库（有 `objects/` 且 HEAD 可读）才改名**。
     * 早期版本把成员名册（`members.json`、`authorized_keys`、`meta.json`）放在
     * 这个**同名目录**里，但它从来就不是仓库 —— 盲目 rename 过去，
     * 后续 `ensureBare` 会把它当仓库打开，然后在写第一个对象时抛
     * `IOException: No such file or directory`，而这个异常发生在
     * `TreeFormatter.insert` 内部，栈里完全看不出跟目录结构有关。
     * 那些旧文件现在也不需要了（名册已经迁到 peers.json），直接删掉。
     */
    private fun migrateLegacyBare() {
        val legacy = Paths.legacyBare(ctx)
        if (!legacy.exists()) return
        runCatching {
            val looksLikeRepo = File(legacy, "objects").isDirectory &&
                File(legacy, "HEAD").isFile
            val target = bareDir()
            if (target.exists()) {
                legacy.deleteRecursively()
                Log.i("MeshRepo", "dropped stale grocery.git (node.git already present)")
            } else if (looksLikeRepo) {
                legacy.renameTo(target)
                Log.i("MeshRepo", "migrated grocery.git -> node.git")
            } else {
                legacy.deleteRecursively()
                Log.i("MeshRepo", "dropped grocery.git: not a git repo (legacy roster dir)")
            }
        }.onFailure { Log.w("MeshRepo", "legacy bare migration failed", it) }
    }

    // ------------------------------------------------------------ 工作区 → 裸仓库

    /**
     * 把工作区当前分支推到裸仓库，供对端 fetch。
     *
     * 必须先探测源 ref 是否存在：刚建好的工作区仓库 HEAD 指向 master，但那个分支
     * 还没有任何提交（unborn HEAD）。此时 `Repository.getBranch()` 照样返回 "master"，
     * 而 JGit 在构造 RemoteRefUpdate 时要解析 `refs/heads/master` 指向的对象，
     * 解析不到就抛 `IOException: Source ref refs/heads/master doesn't resolve to any
     * object`，整个 push 挂掉。裸仓库那边只剩 ensureBare 垫的空提交，对端拉到的是空树。
     *
     * **镜像前必须先把裸仓库里「工作区还不知道」的提交合并回来。**
     * 这里用的是强制推送（`+refs/heads/...`，理由见下），而裸仓库是**对端也会写入**的地方：
     * 对端 push 进来的提交先进裸仓库，再由 postReceive 的后台线程回灌到工作区。
     * 那个回灌是异步的 —— 在线程跑完之前，工作区里没有这批数据，而裸仓库里有。
     * 此时若直接强制推送，就等于用「还没有这份数据的工作区」把「已经有这份数据的裸仓库」
     * 覆盖掉，**对端刚送到、还没落地的新数据被自己抹掉**。
     *
     * 实测（工程外 `_mesh_repro/MeshRepro10.java` 场景 10b）：A 建菜 → A 同步（菜进了
     * B 的 node.git）→ B 的后台回灌尚未执行 → B 点同步 → node.git 里那道菜消失。
     * 用户看到的就是「A 刚建的菜在 A 上也没了」。
     * 场景 10d 验证修复：镜像前若发现裸仓库有工作区还没见过的提交，先回灌再推，
     * 数据保住且工作区同时拿到。
     */
    suspend fun pushWorkspaceToBare() = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(gitRepo.repoDir()).use { git ->
                val branch = git.repository.branch ?: Constants.MASTER
                var head = git.repository.resolve("refs/heads/$branch") ?: return@use

                // 裸仓库里有工作区尚未合并的提交 → 先把它们并进来，再覆盖。
                // 判断用「裸仓库 HEAD 是不是工作区 HEAD 的祖先」而不是「两者是否相等」：
                // 相等只说明外观一致，祖先关系才能表达「它多出来的我都已经有了」。
                val bareHead = runCatching { openBare().use {
                    it.resolve("refs/heads/${bareBranchOf(it)}")
                } }.getOrNull()
                if (bareHead != null && !isAncestor(git, bareHead, head)) {
                    Log.i(TAG, "node.git has commits not in workspace, pulling first")
                    pullBareIntoWorkspace(identitySnapshot)
                    head = git.repository.resolve("refs/heads/$branch") ?: return@use

                    // **回灌之后必须复核一次。** pullBareIntoWorkspace 内部把异常都
                    // runCatching 掉了（它不能让一次合并失败把整个同步链掀翻），
                    // 所以「它返回了」不等于「合并成功了」。若此刻裸仓库里的提交
                    // 仍不是工作区的祖先，说明那批数据还没进工作区 —— 这时候强推
                    // 就是把它们抹掉，宁可这一轮不镜像：对端数据留在 node.git 里，
                    // 下一轮或下一次回灌还能救回来。
                    if (!isAncestor(git, bareHead, head)) {
                        Log.w(
                            TAG,
                            "skip mirror: node.git still ahead of workspace after pull; " +
                                "refusing to overwrite it (would drop peer data)",
                        )
                        return@use
                    }
                }

                git.push()
                    .setRemote(bareDir().absolutePath)
                    // 强制更新：裸仓库是本机镜像，不是独立历史。ensureBare 垫的那个
                    // 空提交和工作区真正的根提交没有共同祖先，不带 + 会被 JGit
                    // 判成 non-fast-forward 直接拒绝，镜像就永远停在空树上。
                    .setRefSpecs(RefSpec("+refs/heads/$branch:refs/heads/$branch"))
                    .call()
                Log.i(TAG, "mirrored $branch@${head.name.take(7)} to node.git")
            }
        }.onFailure { Log.w(TAG, "pushWorkspaceToBare failed", it) }
    }

    /**
     * 回灌时用的身份。`pushWorkspaceToBare` 原本没有 identity 参数，
     * 但它现在可能触发一次合并提交，必须有人署名。
     * 由 [MeshServer] 启动时写入，或第一次带身份的调用补齐。
     */
    @Volatile
    var identitySnapshot: UserIdentity = UserIdentity(name = "dish", email = "dish@localhost")

    private fun bareBranchOf(repo: Repository): String =
        runCatching { repo.branch }.getOrNull() ?: Constants.MASTER

    /** ancestor 是否是 descendant 的祖先（含相等） */
    private fun isAncestor(git: Git, ancestor: ObjectId, descendant: ObjectId): Boolean =
        runCatching {
            if (ancestor == descendant) return true
            RevWalk(git.repository).use { walk ->
                val a = walk.parseCommit(ancestor)
                walk.markStart(walk.parseCommit(descendant))
                var found = false
                for (c in walk) {
                    if (c == a) { found = true; break }
                }
                found
            }
        }.getOrDefault(false)

    // ------------------------------------------------------------ 裸仓库 → 工作区

    /**
     * 把裸仓库里的新提交合并回工作区。
     *
     * 对端 push 进来之后必须调这个，否则本机 UI 看不到家人的改动（数据在，界面不刷新）。
     *
     * **两条铁律**（都是实测撞出来的，见 `_mesh_repro/MeshRepro3.java`）：
     *
     * 1. **不许留 `allowEmpty` 提交。** 旧实现在裸仓库前进过就无条件
     *    `commit().setAllowEmpty(true)`，于是每轮同步两边各长一个「merge: peers (resolved 0)」
     *    空提交；`MeshSync.syncOne` 又因为 `head != remoteRev` 继续 push，
     *    两边永远追不上对方 → 图永不收敛。用户看到的就是
     *    「什么都没改，点同步还是提示拉取 N 个提交」。实测 8 轮空同步各长出 16 个提交。
     *    现在只有**工作区真的有变化**（`commitAll` 有东西可提交）才落提交。
     * 2. **合并成功后不要再补一次提交。** JGit 的 merge 带 `setCommit(true)`
     *    时自己就把结果提交了，再补一个又是一条空提交。
     *
     * 冲突时（`mergeStatus` 不是 successful）必须仲裁 + 提交收尾，否则工作区停在
     * MERGING 状态，之后所有 commit 都抛 "Cannot commit during a merge"。
     */
    suspend fun pullBareIntoWorkspace(identity: UserIdentity): ConflictReport = withContext(Dispatchers.IO) {
        if (!bareDir().exists()) return@withContext ConflictReport.NONE
        runCatching {
            Git.open(gitRepo.repoDir()).use { git ->
                val branch = git.repository.branch ?: Constants.MASTER
                val before = git.repository.resolve(Constants.HEAD)

                // 先 fetch 而不是 pull：pull 在没有配置 remote 时会抛
                // InvalidConfigurationException（工作区仓库从不配置 remote，
                // 地址都是每次现传的），那条路径 100% 走不通，白白丢掉一次机会。
                git.fetch()
                    .setRemote(bareDir().absolutePath)
                    .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/node/*"))
                    .call()
                val target = git.repository.resolve("refs/remotes/node/$branch")
                if (target == null || target == before) return@withContext ConflictReport.NONE

                var report = ConflictReport.NONE
                val merged = runCatching {
                    git.merge().include(target)
                        .setStrategy(MergeStrategy.RECURSIVE)
                        .setMessage("merge: peers")
                        .call()
                }.getOrNull()

                if (merged == null || !merged.mergeStatus.isSuccessful) {
                    // 显式传 before / target：JGit 在 CheckoutConflictException 路径上
                    // 不写 MERGE_HEAD，靠它取 base 必然拿不到（坑 42）
                    report = gitRepo.resolveConflictsKeepingNewest(before, target)
                }
                // 仲裁后（或合并成功但工作区有遗留改动）才提交；没有变化就不提交。
                // commitAll 自己会判空，而且带「有冲突标记就拒绝提交」的保护。
                gitRepo.commitAll(identity, "merge: peers (resolved ${report.entries.size})")
                report
            }
        }.getOrElse {
            Log.w(TAG, "pullBareIntoWorkspace failed", it)
            ConflictReport.NONE
        }
    }

    /** 裸仓库当前分支名（对端用来对齐 refspec）。空仓库时退回 master */
    fun bareBranch(): String = runCatching {
        openBare().use { it.branch ?: Constants.MASTER }
    }.getOrDefault(Constants.MASTER)

    companion object {
        private const val TAG = "MeshRepo"
    }
}
