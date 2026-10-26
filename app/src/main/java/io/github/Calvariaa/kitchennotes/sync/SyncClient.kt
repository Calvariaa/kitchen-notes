package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import io.github.Calvariaa.kitchennotes.data.FamilyGroup
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.SettingsStore
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.transport.RefSpec
import java.io.File

/**
 * git 同步客户端。
 *
 * 一次「同步」= fetch → 本地提交 → 合并（按 updatedAt 仲裁） → push。
 * 顺序很重要：
 *  - 先 fetch 是为了合并前拿到最新远端，避免 push 被拒后才发现要 rebase。
 *  - 本地提交放中间，保证工作区干净，merge 才有意义。
 *  - push 放最后，因为 push 是非快进会被拒绝，而我们已经合并过，天然是快进。
 *
 * 传输选的是 **git smart HTTP**（主机端 JGit 的 GitServlet，客户端 JGit 的 TransportHttp）。
 * 没走 SSH 的原因：Android 上 JSch 的 host key 校验、known_hosts 写权限、keystore 格式
 * 都要额外伺候，而 HTTP 只需一个 URL。SSH 密钥仍然保留身份作用——见 MembersApi 的注释。
 */
class SyncClient(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val gitRepo: GitRepo,
    private val hostRepo: HostRepository,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private fun remoteUrl(group: FamilyGroup): String {
        val port = settings.get().serverPort
        val host = group.hostAddress.substringBefore(":")
        val p = group.hostAddress.substringAfter(":", port.toString()).toIntOrNull() ?: port
        return "http://$host:$p/grocery.git"
    }

    /**
     * @param member true 表示要往返主机（fetch + merge + push）；false 表示只做本地提交。
     *   主机的本地提交不该走网络路径——它和裸仓库在同一进程里，由 GitServerHost 负责推拉。
     */
    suspend fun sync(identity: UserIdentity, member: Boolean = true): SyncResult = withContext(Dispatchers.IO) {
        val group = settings.group()
            ?: throw SyncError(SyncError.Kind.NO_GROUP)

        // 主机自己既当服务端又当客户端会锁死（同一个 JGit 仓库两处打开），
        // 所以主机的「同步」只需要本地提交，不需要 push。
        if (group.role == FamilyGroup.Role.HOST || !member) {
            gitRepo.commitAll(identity, "recipe:sync ${System.currentTimeMillis()}")
            return@withContext SyncResult(
                parts = listOf(
                    if (group.role == FamilyGroup.Role.HOST) SyncPart.HOST_LOCAL
                    else SyncPart.COMMITTED_LOCAL,
                ),
            )
        }

        gitRepo.ensureInitialized(identity)

        // 全新成员还没有任何提交，fetch 回来的远端分支没法 merge（没有共同祖先）。
        // 先走 adoptRemote 把本地直接对齐到远端，这是最干净也不会丢东西的做法
        // （本地本来就没提交，谈不上覆盖）。
        if (!hasLocalCommits()) {
            return@withContext adoptRemote(identity, remoteUrl(group), group)
        }

        val url = remoteUrl(group)
        Git.open(gitRepo.repoDir()).use { git ->
            val branch = git.repository.branch ?: Constants.MASTER

            // 1. fetch
            runCatching {
                git.fetch()
                    .setRemote(url)
                    .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                    .setTimeout(60)
                    .call()
            }.getOrElse {
                throw SyncError(SyncError.Kind.FETCH_FAILED, it.message?.take(160))
            }

            // 2. 本地提交
            gitRepo.commitAll(identity, "recipe:sync ${System.currentTimeMillis()}")

            val head = runCatching { git.repository.resolve(Constants.HEAD) }.getOrNull()
            val remoteRev = runCatching {
                git.repository.resolve("refs/remotes/origin/$branch")
            }.getOrNull()

            var pulled = 0
            var conflicts = 0

            // 3. 合并（只有远端确实有进展时才做，否则 merge 会白跑一遍）
            if (remoteRev != null && head != null && remoteRev.name != head.name) {
                val result = runCatching {
                    git.merge()
                        .include(remoteRev)
                        .setStrategy(MergeStrategy.RECURSIVE)
                        .setFastForward(MergeCommand.FastForwardMode.FF)
                        .setCommit(true)
                        .setMessage("merge: family sync")
                        .call()
                }.getOrNull()

                pulled = runCatching {
                    git.log().addRange(head, remoteRev).call().count()
                }.getOrNull() ?: 0

                if (result == null || !result.mergeStatus.isSuccessful) {
                    // 冲突：交给按 updatedAt 取新的仲裁
                    conflicts = gitRepo.resolveConflictsKeepingNewest()
                    if (conflicts > 0) {
                        git.add().addFilepattern(".").call()
                        git.commit()
                            .setMessage("merge: resolve $conflicts conflicts by newest")
                            .setAllowEmpty(true)
                            .call()
                    }
                }
            }

            // 4. push
            val pushed = runCatching {
                git.push()
                    .setRemote(url)
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .setTimeout(60)
                    .call()
            }.getOrElse {
                throw SyncError(SyncError.Kind.PUSH_FAILED, it.message?.take(160))
            }

            val upToDate = pushed.all { it.remoteUpdates.isEmpty() }
            val parts = buildList {
                if (pulled > 0) add(SyncPart.PULLED)
                if (conflicts > 0) add(SyncPart.CONFLICTS)
                if (!upToDate) add(SyncPart.PUSHED)
                if (isEmpty()) add(SyncPart.UP_TO_DATE)
            }

            SyncResult(
                pulledCommits = pulled,
                conflictsResolved = conflicts,
                pushedCommits = if (upToDate) 0 else 1,
                parts = parts,
            )
        }
    }

    /** 本地是否已经有提交（没有提交的仓库 merge 远端分支会因为没有共同祖先而失败） */
    private fun hasLocalCommits(): Boolean = runCatching {
        Git.open(gitRepo.repoDir()).use { git ->
            git.repository.resolve(Constants.HEAD) != null
        }
    }.getOrDefault(false)

    /**
     * 空仓库的成员第一次同步：直接对齐远端分支。
     *
     * 顺带处理主机也还是空仓库的情况——那样 fetch 回来没有远端分支，
     * 就垫一个空初始提交把两边链路接上，之后正常 merge。
     */
    private suspend fun adoptRemote(
        identity: UserIdentity,
        url: String,
        group: FamilyGroup,
    ): SyncResult {
        Git.open(gitRepo.repoDir()).use { git ->
            val fetchResult = runCatching {
                git.fetch()
                    .setRemote(url)
                    .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                    .setTimeout(60)
                    .call()
            }.getOrElse {
                throw SyncError(SyncError.Kind.FETCH_FAILED, it.message?.take(160))
            }

            // 主机用的分支名可能不是本地默认分支，跟着主机走，否则后面 push 的 refspec 对不上
            // 注意：FetchResult 不是集合，要显式遍历 getAdvertisedRefs()
            val branch = fetchResult.advertisedRefs
                .mapNotNull { it.name }
                .firstOrNull { it.startsWith("refs/heads/") }
                ?.removePrefix("refs/heads/")
                ?: Constants.MASTER

            val remoteRev = git.repository.resolve("refs/remotes/origin/$branch")
            val ident = org.eclipse.jgit.lib.PersonIdent(
                identity.name.ifBlank { "dish" },
                identity.email.ifBlank { "dish@localhost" },
            )

            if (remoteRev == null) {
                // 主机那里也还是空的：垫一个空提交，双方就有了共同祖先
                if (git.repository.branch != branch) {
                    git.branchCreate().setName(branch).setForce(true).call()
                    git.checkout().setName(branch).call()
                }
                git.commit()
                    .setMessage("init: join ${group.name}")
                    .setAllowEmpty(true)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call()
                git.push()
                    .setRemote(url)
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .setTimeout(60)
                    .call()
                return SyncResult(
                    parts = listOf(SyncPart.ADOPTED_EMPTY),
                    groupName = group.name,
                )
            }

            // 把本地分支硬指向远端，然后 checkout 到该提交。
            // 本地没有任何提交，不存在覆盖用户数据的风险；用 reset 而不是 setStartPoint
            // 是因为 CheckoutCommand 的 setStartPoint 在 detached/空仓库上行为不稳。
            val update = git.repository.updateRef("refs/heads/$branch")
            update.setNewObjectId(remoteRev)
            update.setForceUpdate(true)
            update.update()

            git.checkout().setName(branch).setForced(true).call()
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(remoteRev.name).call()

            val count = runCatching { git.log().add(remoteRev).call().count() }.getOrDefault(0)
            return SyncResult(pulledCommits = count, parts = listOf(SyncPart.PULLED))
        }
    }
}

/**
 * 主机侧的仓库生命周期：把裸仓库和服务端需要的元数据准备好。
 *
 * 裸仓库 `grocery.git` 与工作区 `repo/` 的关系：
 *  - 成员 push 到 grocery.git
 *  - 主机自己仍用 repo/ 工作区编辑，每次同步时把 grocery.git 的更新 pull 进 repo/，
 *    再把本地提交推到 grocery.git —— 由 GitServer 内部的 postReceive 回调触发。
 */
class HostRepository(
    private val ctx: Context,
    private val settings: SettingsStore,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Member(
        @SerialName("name") val name: String,
        @SerialName("email") val email: String,
        @SerialName("publicKey") val publicKey: String,
        @SerialName("joinedAt") val joinedAt: Long,
    )

    @Serializable
    data class GroupMeta(
        @SerialName("groupName") val groupName: String,
        @SerialName("hostName") val hostName: String,
        @SerialName("token") val token: String,
        @SerialName("members") val members: List<Member> = emptyList(),
    )

    private fun metaFile() = File(Paths.bare(ctx), "meta.json")
    private fun authFile() = File(Paths.bare(ctx), "authorized_keys")
    private fun membersFile() = File(Paths.bare(ctx), "members.json")

    fun bareDir(): File = File(Paths.bare(ctx), "grocery.git")

    fun readMeta(): GroupMeta = runCatching {
        if (metaFile().exists()) json.decodeFromString<GroupMeta>(metaFile().readText())
        else GroupMeta(FALLBACK_GROUP_NAME, FALLBACK_HOST_NAME, randomToken())
    }.getOrDefault(GroupMeta(FALLBACK_GROUP_NAME, FALLBACK_HOST_NAME, randomToken()))

    fun writeMeta(meta: GroupMeta) {
        Paths.bare(ctx).mkdirs()
        metaFile().writeText(json.encodeToString(GroupMeta.serializer(), meta))
        membersFile().writeText(json.encodeToString(GroupMeta.serializer(), meta))
        renderAuthorizedKeys(meta)
    }

    private fun renderAuthorizedKeys(meta: GroupMeta) {
        val lines = meta.members
            .filter { it.publicKey.isNotBlank() }
            .map { "${it.publicKey.trim()} ${it.email}" }
        File(Paths.bare(ctx), "ssh").mkdirs()
        authFile().writeText(lines.joinToString("\n").ifBlank { "" })
    }

    fun addMember(member: Member) {
        val meta = readMeta()
        val updated = meta.copy(
            members = (meta.members.filterNot { it.email == member.email } + member),
        )
        writeMeta(updated)
    }

    fun removeMember(email: String) {
        val meta = readMeta()
        writeMeta(meta.copy(members = meta.members.filterNot { it.email == email }))
    }

    /** 确保主机自己的身份也登记在成员里，方便 UI 显示「我」 */
    fun ensureSelfRegistered(identity: UserIdentity) {
        if (identity.email.isBlank()) return
        val meta = readMeta()
        if (meta.members.any { it.email == identity.email }) return
        addMember(
            Member(
                name = identity.name,
                email = identity.email,
                publicKey = identity.sshPublicKey,
                joinedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        /**
         * 主机元数据还没写出来的极短暂窗口里用的占位值。
         * 真正的默认名由 UI 层按当前语言传进 startHost()，这里只保证不是空串。
         */
        const val FALLBACK_GROUP_NAME = "Kitchen"
        const val FALLBACK_HOST_NAME = "Host"
    }

    fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        return (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
