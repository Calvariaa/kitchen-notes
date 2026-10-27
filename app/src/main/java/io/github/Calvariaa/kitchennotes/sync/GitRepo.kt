package io.github.Calvariaa.kitchennotes.sync

import android.content.Context
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
     * 冲突仲裁：pull 之后工作区可能被写入冲突标记。
     * 我们不保留标记，而是把两份都解析出来，留 updatedAt 较大的那份，另一份存进 conflict 目录。
     *
     * 菜谱（dishes/）和菜单（menus/）都走这里。只扫 dishes/ 的话，两边同时改同一餐
     * （比如各给一道菜打了分）会留下带冲突标记的 JSON，反序列化直接失败，
     * `MenuRepository.all()` 的 runCatching 把这餐静默吞掉，用户看到的是「菜单凭空消失」。
     *
     * @return 被仲裁的记录数（菜谱 + 菜单）
     */
    suspend fun resolveConflictsKeepingNewest(): Int = withContext(Dispatchers.IO) {
        resolveDir(Paths.dishesDir(ctx), Recipe.serializer()) +
            resolveDir(File(Paths.repo(ctx), "menus"), Menu.serializer())
    }

    private fun <T> resolveDir(
        dir: File,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Int {
        val resolved = mutableListOf<T>()
        val backupDir = File(Paths.root(ctx), "conflicts").apply { mkdirs() }

        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            if (!text.contains(CONFLICT_START)) return@forEach

            val ours = sliceSide(text, "ours")
            val theirs = sliceSide(text, "theirs")
            val a = ours?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            val b = theirs?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

            val updatedAtOf: (T) -> Long = { e ->
                when (e) {
                    is Recipe -> e.updatedAt
                    is Menu -> e.updatedAt
                    else -> 0L
                }
            }
            val idOf: (T) -> String = { e ->
                when (e) {
                    is Recipe -> e.id
                    is Menu -> e.id
                    else -> file.name.removeSuffix(".json")
                }
            }

            val winner = listOfNotNull(a, b).maxByOrNull { updatedAtOf(it) }
            val loser = if (winner === a) b else a

            if (winner != null) {
                file.writeText(json.encodeToString(serializer, winner))
                resolved += winner
            }
            if (loser != null) {
                File(backupDir, "${idOf(loser)}-${updatedAtOf(loser)}.json")
                    .writeText(json.encodeToString(serializer, loser))
            }
            // 记录合并，方便 UI 提示「有 N 条记录发生冲突，已保留较新版」
            File(backupDir, "merge.log").appendText(
                "${java.time.Instant.now()} ${file.name} winner=${winner?.let(updatedAtOf)} loser=${loser?.let(updatedAtOf)}\n",
            )
        }
        return resolved.size
    }

    private fun sliceSide(text: String, side: String): String? {
        val startMark = if (side == "ours") "<<<<<<<" else "======="
        val endMark = if (side == "ours") "=======" else ">>>>>>>"
        val start = text.indexOf(startMark)
        if (start < 0) return null
        val afterStart = text.indexOf('\n', start).let { if (it < 0) text.length else it + 1 }
        val end = text.indexOf(endMark, afterStart)
        if (end < 0) return null
        return text.substring(afterStart, end)
            // ours 侧可能内部还嵌着 marker，去掉
            .replace(Regex("[<=>]{7}.*\\n"), "")
            .trim()
            .takeIf { it.startsWith("{") }
    }

    companion object {
        const val CONFLICT_START = "<<<<<<<"
        val STRATEGY: MergeStrategy get() = MergeStrategy.RECURSIVE
    }
}
