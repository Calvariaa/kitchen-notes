package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
import android.util.Log
import io.github.Calvariaa.kitchennotes.data.Menu
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.Recipe
import io.github.Calvariaa.kitchennotes.data.RecipeRepository
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * 本机仓库的初始化与本地提交。
 *
 * 为什么不用 JGit 的 MergeCommand 做冲突仲裁，而是自己实现「按 updatedAt 取新」：
 * JGit 的三方合并对 JSON 只会报 CONFLICTING，需要人肉介入。菜谱这种数据没有结构冲突的必要——
 * 两个人改了同一道菜，谁后改的保留谁的，另一份进冲突备份目录，用户还能在 UI 里看到。
 * 这比让用户在饭点手动 merge 冲突标记友善得多。
 *
 * 提交粒度：一次用户动作一个 commit（新增/生成/编辑/删除），message 里带 recipe id，
 * 冲突仲裁时可以直接从提交链里定位到冲突的那条菜谱。
 */
class GitRepo(private val ctx: Context, private val repo: RecipeRepository) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun repoDir(): File = Paths.repo(ctx)

    fun open(): Repository {
        val dir = repoDir()
        val gitDir = File(dir, ".git")
        return FileRepositoryBuilder()
            .setGitDir(gitDir)
            .setWorkTree(dir)
            .readEnvironment()
            .build()
    }

    /** 首次运行或从零开始：初始化仓库并写基础文件 */
    suspend fun ensureInitialized(identity: UserIdentity) = withContext(Dispatchers.IO) {
        val dir = repoDir()
        val gitDir = File(dir, ".git")
        if (!gitDir.exists()) {
            val repository = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .setWorkTree(dir)
                .build()
            // 参数是 bare，不是「是否创建目录」。传 true 会把工作区仓库标成裸仓库
            // （config 里落 bare = true），之后所有 add/commit 都抛
            // NoWorkTreeException: Bare Repository has neither a working tree, nor an index。
            // 表现就是「点创建家庭 → 主机起不来」，且该状态会被保存到磁盘，重装也不消失。
            repository.create(false)
            // 在 Android 上 JGit 遇到符号链接/文件权限会报错，关掉相关假设
            repository.config.apply {
                setBoolean("core", null, "fileMode", false)
                setBoolean("core", null, "ignoreCase", false)
                setString("core", null, "autocrlf", "false")
                setInt("gc", null, "auto", 0)
                save()
            }
            repository.close()
        } else {
            // 自愈：老版本把 create(true) 当「创建目录」用，给工作区仓库写进了
            // core.bare = true，此后 add/commit 全部抛 NoWorkTreeException，
            // 而且这个标记会一直留在 config 里，重装 App 也不消失（files 目录不清）。
            // 只改 bare 位，不动 objects / refs，用户数据零损失。
            healBareFlag(gitDir, dir)
        }
        // 两张必需文件
        File(dir, ".gitattributes").takeIf { !it.exists() }?.writeText(
            "*.json text eol=lf\n*.jpg binary\n*.png binary\n*.webp binary\n*.gif binary\n",
        )
        Paths.dishesDir(ctx)
        Paths.imagesDir(ctx)
        // 菜单目录同样要在首次初始化时就建好。git 不跟踪空目录，所以塞一个 .gitkeep，
        // 否则「一道菜单都没记」的仓库 clone 下来没有 menus/ 目录，
        // 与主机端布局不一致，排查同步问题时容易误判。
        File(Paths.repo(ctx), "menus").mkdirs()
        File(Paths.repo(ctx), "menus/.gitkeep").takeIf { !it.exists() }?.writeText("")
    }

    /**
     * 修正被误标成裸仓库的工作区仓库。
     *
     * `Repository.create(true)` 的第一个参数是 **bare**，不是「创建目录」。
     * 早期代码传了 true，于是 `repo/.git/config` 里留下 `bare = true`，
     * 之后 `git.add()` 就抛 `NoWorkTreeException: Bare Repository has neither
     * a working tree, nor an index` —— 用户看到的是「创建家庭后主机起不来」。
     *
     * 这个标记写在 config 里而不是派生自目录结构，所以重装 App（不清 files）也不会自愈，
     * 必须在每次初始化时主动纠一遍。
     */
    private fun healBareFlag(gitDir: File, workTree: File) {
        runCatching {
            FileRepositoryBuilder()
                .setGitDir(gitDir)
                .setWorkTree(workTree)
                .build()
                .use { repo ->
                    val cfg = repo.config
                    if (cfg.getBoolean("core", "bare", false)) {
                        cfg.setBoolean("core", null, "bare", false)
                        cfg.save()
                        android.util.Log.w("GitRepo", "healed core.bare=true on work tree repo")
                    }
                }
        }
    }

    // ------------------------------------------------------------ 本地提交

    /**
     * 把工作区当前状态提交一次。没有变化时返回 null。
     * @param message 提交信息，约定格式 `${动作}: ${recipeId}`，例如 `recipe:add a1b2...`
     */
    suspend fun commitAll(identity: UserIdentity, message: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized(identity)
        // **有未仲裁的冲突标记时一律不提交。**
        // 曾经这里不检查：pull 失败后仓库停在 MERGING，回退路径直接 add + commit，
        // 把带 `<<<<<<<` 的 JSON 提交并推给对端，于是两台设备的记录一起变成
        // 「解析失败 → 被 runCatching 吞掉 → 从 UI 消失」。
        // 宁可这一轮不同步，也不能把损坏状态传染出去。
        if (hasUnresolvedConflicts()) {
            Log.w(TAG, "skip commit: unresolved conflict markers in work tree")
            return@withContext null
        }
        Git.open(repoDir()).use { git ->
            git.add().addFilepattern(".").call()
            // 处理删除（图片被清理、菜谱文件被移除）
            git.add().addFilepattern(".").setUpdate(true).call()

            val status = git.status().call()
            val changed = status.added + status.changed + status.modified + status.removed + status.untracked
            if (changed.isEmpty()) return@withContext null

            val ident = PersonIdent(identity.name.ifBlank { "dish" }, identity.email.ifBlank { "dish@localhost" })
            val commit = git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                .setAllowEmpty(false)
                .call()
            commit.name
        }
    }

    fun headOf(git: Git): ObjectId? =
        runCatching { git.repository.resolve(Constants.HEAD) }.getOrNull()

    /** 当前分支短名，例如 main */
    fun currentBranch(): String = runCatching {
        open().use { it.branch }
    }.getOrDefault(Constants.MASTER)

    /**
     * 读出一批文件在「共同祖先」里的内容（三方合并的 base 侧）。
     *
     * **为什么必须取 base**：只比较 ours/theirs 两份文本，分不清「一边没动」和
     * 「两边改成一样」。离线各改不同字段时，没被改的那一侧正好等于 base，
     * 如果不知道 base，就会把「对方没动这个字段」误判成「对方也改了」，
     * 于是较晚保存的那份整体胜出、另一边的改动被丢 —— 实测见 MeshRepro7 场景 1。
     *
     * **为什么显式传两个 commit 而不是读 `MERGE_HEAD`**：JGit 的 `merge()` 在
     * 工作区会被覆盖时抛 `CheckoutConflictException`，而**抛异常的那条路径根本不写
     * `MERGE_HEAD`**。于是 `repo.resolve("MERGE_HEAD")` 恒为 null，base 永远取不到，
     * 三方合并静默退化成二选一 —— 症状和没改一样，而且完全不报错（实测 MeshRepro8：
     * 日志里 `base=无`）。调用方手里本来就有 `before`（ours）和 `remoteRev`（theirs），
     * 直接传进来最可靠。
     *
     * @return 相对路径 → 文件内容；读不到的一律不进 map（调用方按「base 未知」保守处理）
     */
    fun readMergeBaseFiles(
        git: Git,
        oursCommit: ObjectId?,
        theirsCommit: ObjectId?,
        relativePaths: Collection<String>,
    ): Map<String, String> {
        if (relativePaths.isEmpty() || oursCommit == null || theirsCommit == null) return emptyMap()
        return runCatching {
            val repo = git.repository
            val baseTree = repo.newObjectReader().use { reader ->
                RevWalk(reader).use { walk ->
                    walk.setRevFilter(RevFilter.MERGE_BASE)
                    walk.markStart(walk.parseCommit(oursCommit))
                    walk.markStart(walk.parseCommit(theirsCommit))
                    walk.next()?.tree ?: return@runCatching emptyMap()
                }
            }
            val out = HashMap<String, String>()
            relativePaths.forEach { path ->
                runCatching {
                    val id = repo.resolve("${baseTree.name()}:$path") ?: return@forEach
                    out[path] = String(repo.open(id).bytes, Charsets.UTF_8)
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * 冲突仲裁：pull/merge 之后工作区可能被写入冲突标记。
     *
     * 优先级：**字段级三方合并** > 整文件取 `updatedAt` 较大的那份。
     *
     * 前者能保住「一台手机改简介、另一台改小贴士」这种两边都有效的改动；
     * 后者只在三方合并不可用（base 取不到、JSON 坏了）时兜底，
     * 且**落败方一定落盘备份**到 `files/store/conflicts/`，用户可以人工找回。
     *
     * 菜谱（`dishes/`）和菜单（`menus/`）都走这里。
     *
     * @param oursCommit  合并前本机的 HEAD（冲突标记里的 HEAD 侧）
     * @param theirsCommit 被合进来的对端提交（冲突标记里的另一侧）
     * @return 结构化清单，UI 拿它渲染「这次撞了哪些、谁被盖了」
     */
    suspend fun resolveConflictsKeepingNewest(
        oursCommit: ObjectId? = null,
        theirsCommit: ObjectId? = null,
    ): ConflictReport = withContext(Dispatchers.IO) {
        Git.open(repoDir()).use { git ->
            val dishDir = Paths.dishesDir(ctx)
            val menuDir = File(Paths.repo(ctx), "menus")
            val dishes = resolveDir(git, dishDir, "dishes", ConflictEntry.Kind.DISH, oursCommit, theirsCommit)
            val menus = resolveDir(git, menuDir, "menus", ConflictEntry.Kind.MENU, oursCommit, theirsCommit)
            ConflictReport(entries = dishes.entries + menus.entries, unresolved = dishes.unresolved + menus.unresolved)
        }
    }

    /** 工作区里还有没有未清除的冲突标记。有就不许提交（见 [commitAll]） */
    fun hasUnresolvedConflicts(): Boolean {
        val dirs = listOf(Paths.dishesDir(ctx), File(Paths.repo(ctx), "menus"))
        return dirs.any { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.any { f ->
                runCatching { ConflictResolver.hasConflict(f.readText()) }.getOrDefault(false)
            } == true
        }
    }

    /** [resolveDir] 的内部结果：解出来的条目 + 解不掉的残留数 */
    private class DirResolution(val entries: List<ConflictEntry>, val unresolved: Int)

    private fun resolveDir(
        git: Git,
        dir: File,
        label: String,
        kind: ConflictEntry.Kind,
        oursCommit: ObjectId?,
        theirsCommit: ObjectId?,
    ): DirResolution {
        val backupDir = File(Paths.root(ctx), "conflicts").apply { mkdirs() }
        val entries = mutableListOf<ConflictEntry>()
        var unresolvedCount = 0

        // 先扫一遍找出真有冲突标记的文件，再一次性把它们的 base 版本读出来。
        // 逐个文件去 walk 提交图会很慢（家用设备上几十个文件就能感到卡顿）。
        val conflicted = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
                if (ConflictResolver.hasConflict(text)) f to text else null
            }
            ?: return DirResolution(emptyList(), 0)
        if (conflicted.isEmpty()) return DirResolution(emptyList(), 0)

        val repoRoot = Paths.repo(ctx)
        val relPaths = conflicted.map {
            it.first.absolutePath.removePrefix(repoRoot.absolutePath).trimStart(File.separatorChar)
        }
        val baseTexts = readMergeBaseFiles(git, oursCommit, theirsCommit, relPaths)

        // 打平仲裁用的键：两台设备在同一次合并里看到的是同一对提交，
        // 只是 ours/theirs 角色互换，所以拿 SHA 定序能保证两边结论一致（见 oursWinsTie）
        val oursKey = oursCommit?.name()
        val theirsKey = theirsCommit?.name()

        conflicted.forEachIndexed { index, (file, text) ->
            val resolution = runCatching { ConflictResolver.arbitrate(text, oursKey, theirsKey) }.getOrNull()
            if (resolution == null) {
                // 两边都解不出来（被外部工具写坏、标记不配对）。**保留原文件不提交**，
                // 让 callers 看到 hasUnresolvedConflicts()=true 而停手，
                // 数据留在磁盘上还有人工恢复的机会 —— 旧实现这里什么都不做却照样提交。
                Log.w(TAG, "unresolvable conflict kept as-is: ${file.name}")
                unresolvedCount++
                return@forEachIndexed
            }

            // **优先做字段级三方合并，而不是整文件二选一。**
            // 一台手机改简介、另一台改小贴士，两份改动落在同一个文件上；
            // 取 updatedAt 大的那份会把另一个字段的改动整个丢掉（实测 MeshRepro7 场景 1）。
            val base = baseTexts[relPaths[index]]
            val three = runCatching {
                ConflictResolver.mergeThreeWay(
                    resolution.oursRaw, base, resolution.theirsRaw, oursKey, theirsKey,
                )
            }.getOrNull()

            val finalText = three?.text ?: resolution.text
            val stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

            runCatching {
                file.writeText(finalText)

                // **落败/被覆盖的那一份必须落盘**，哪怕三方合并成功。
                // 以前这里只往 merge.log 写一行时间戳，用户想找回被覆盖的版本没有任何途径。
                // 文件名带时间戳，同一份记录多次冲突不会互相覆盖。
                val backupName = "${file.nameWithoutExtension}-$stamp.json"
                File(backupDir, backupName).writeText(resolution.loserRaw)

                entries += ConflictEntry(
                    kind = kind,
                    id = file.nameWithoutExtension,
                    fields = three?.conflicts.orEmpty(),
                    autoMerged = three?.autoMerged.orEmpty(),
                    backupName = backupName,
                )

                File(backupDir, "merge.log").appendText(
                    "$stamp $label/${file.name} " +
                        if (three == null) {
                            "fields=unavailable kept updatedAt=${resolution.updatedAt} " +
                                "side=${if (resolution.oursKept) "ours" else "theirs"}\n"
                        } else {
                            "autoMerged=${three.autoMerged} conflicts=${three.conflicts} " +
                                "resolved=${three.merged}\n"
                        },
                )
                if (three != null && three.conflicts.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "field conflicts in ${file.name}: ${three.conflicts} " +
                            "(kept newer side; loser backed up to conflicts/)",
                    )
                }
            }.onFailure { Log.w(TAG, "write arbitrated ${file.name} failed", it) }
        }
        return DirResolution(entries, unresolvedCount)
    }

    companion object {
        const val CONFLICT_START = ConflictResolver.CONFLICT_START
        val STRATEGY: MergeStrategy get() = MergeStrategy.RECURSIVE
        private const val TAG = "GitRepo"
    }
}
