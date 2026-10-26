package com.dishplanner.sync

import android.content.Context
import com.dishplanner.data.Paths
import com.dishplanner.data.Recipe
import com.dishplanner.data.RecipeRepository
import com.dishplanner.data.UserIdentity
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
            repository.create(true)
            // 在 Android 上 JGit 遇到符号链接/文件权限会报错，关掉相关假设
            repository.config.apply {
                setBoolean("core", null, "fileMode", false)
                setBoolean("core", null, "ignoreCase", false)
                setString("core", null, "autocrlf", "false")
                setInt("gc", null, "auto", 0)
                save()
            }
            repository.close()
        }
        // 两张必需文件
        File(dir, ".gitattributes").takeIf { !it.exists() }?.writeText(
            "*.json text eol=lf\n*.jpg binary\n*.png binary\n*.webp binary\n*.gif binary\n",
        )
        Paths.dishesDir(ctx)
        Paths.imagesDir(ctx)
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
     * @return 被仲裁的菜谱条数
     */
    suspend fun resolveConflictsKeepingNewest(): List<Recipe> = withContext(Dispatchers.IO) {
        val dir = Paths.dishesDir(ctx)
        val resolved = mutableListOf<Recipe>()
        val backupDir = File(Paths.root(ctx), "conflicts").apply { mkdirs() }

        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            if (!text.contains(CONFLICT_START)) return@forEach

            val ours = sliceSide(text, "ours")
            val theirs = sliceSide(text, "theirs")
            val a = ours?.let { runCatching { json.decodeFromString<Recipe>(it) }.getOrNull() }
            val b = theirs?.let { runCatching { json.decodeFromString<Recipe>(it) }.getOrNull() }

            val winner = listOfNotNull(a, b).maxByOrNull { it.updatedAt }
            val loser = if (winner === a) b else a

            if (winner != null) {
                file.writeText(json.encodeToString(Recipe.serializer(), winner))
                resolved += winner
            }
            if (loser != null) {
                File(backupDir, "${loser.id}-${loser.updatedAt}.json")
                    .writeText(json.encodeToString(Recipe.serializer(), loser))
            }
            // 记录合并，方便 UI 提示「有 N 条记录发生冲突，已保留较新版」
            File(backupDir, "merge.log").appendText(
                "${java.time.Instant.now()} ${file.name} winner=${winner?.updatedAt} loser=${loser?.updatedAt}\n",
            )
        }
        resolved
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
