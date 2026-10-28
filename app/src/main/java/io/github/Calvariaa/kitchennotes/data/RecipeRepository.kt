package io.github.Calvariaa.kitchennotes.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 仓库目录布局（同时也是 git 工作区布局）：
 *
 * ```
 * appFiles/store/
 *   repo/                        <- 工作区（git work tree）；UI 读的就是这里
 *     dishes/<id>.json
 *     menus/<id>.json
 *     images/<yyyy>/<MM>/<sha8>.<ext>
 *     .git/
 *   node.git/                    <- 本机对外的裸仓库，P2P 对端 fetch/push 的目标
 *   mesh/                        <- 节点身份与已知成员（公钥）
 *   peers.json  family.json
 * ```
 *
 * P2P 下每台设备都有 `node.git`，没有「只有主机才有」的目录了。
 * 图片命名用内容散列：同一张图重复上传不会产生新对象，git 里也不会膨胀。
 */
object Paths {
    fun root(ctx: Context): File = File(ctx.filesDir, "store").apply { mkdirs() }
    fun repo(ctx: Context): File = File(root(ctx), "repo").apply { mkdirs() }

    /** 本机对外的裸仓库。对端连 `http://<ip>:7411/node.git`，不必知道设备名 */
    fun bare(ctx: Context): File = File(root(ctx), "node.git")

    /** 主机模式时代的裸仓库。首次启动由 MeshRepo 迁移进 [bare] 后即废弃 */
    fun legacyBare(ctx: Context): File = File(root(ctx), "grocery.git")

    /** 节点身份与成员公钥。P2P 下每台设备都有，不再是主机专属 */
    fun meshDir(ctx: Context): File = File(root(ctx), "mesh").apply { mkdirs() }

    fun dishesDir(ctx: Context): File = File(repo(ctx), "dishes").apply { mkdirs() }
    fun imagesDir(ctx: Context): File = File(repo(ctx), "images").apply { mkdirs() }
    fun inboxDir(ctx: Context): File = File(root(ctx), "inbox").apply { mkdirs() }

    /**
     * 下载下来的 APK 落这里。
     *
     * 放 filesDir 而不是 cacheDir：cache 随时可能被系统清掉，
     * 用户点了「下载」再去点「安装」时文件已经消失的体验很差。
     * 安装完由 UpdateViewModel 删掉，不长期占空间。
     */
    fun updatesDir(ctx: Context): File = File(root(ctx), "updates").apply { mkdirs() }
    fun legacyRecipesDir(ctx: Context): File = File(root(ctx), "recipes").apply { mkdirs() }
}

/**
 * 菜谱仓库。
 *
 * 为什么是「一菜一文件」而不是一个大 JSON 或 SQLite：
 * 需求要求用 git 做局域网同步。git 的合并粒度是文件，一条菜谱一个文件才能让两个人离线各加一道菜、
 * 之后再 push 时不互相踩。SQLite 二进制无法合并，大 JSON 每次改一个字就整文件冲突。
 */
class RecipeRepository(private val ctx: Context) {

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private companion object {
        const val TAG = "RecipeRepository"
    }

    // ---------------------------------------------------------------- 读

    suspend fun all(): List<Recipe> = withContext(Dispatchers.IO) {
        val dir = Paths.dishesDir(ctx)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<Recipe>(f.readText()) }
                    // 单个文件读坏了不能拖垮整个列表（一条坏记录不该让整页空白），
                    // **但必须留日志**：静默吞掉会让「菜谱凭空消失」变成一个
                    // 无从查证的幽灵问题 —— 磁盘上文件还在、界面上没有、日志里也没有。
                    .onFailure { Log.w(TAG, "skip unreadable recipe file: ${f.name}", it) }
                    .getOrNull()
            }
            ?.filter { !it.deleted }
            ?.sortedWith(compareByDescending<Recipe> { it.updatedAt })
            ?: emptyList()
    }

    suspend fun allIncludingDeleted(): List<Recipe> = withContext(Dispatchers.IO) {
        Paths.dishesDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<Recipe>(f.readText()) }
                    .onFailure { Log.w(TAG, "skip unreadable recipe file: ${f.name}", it) }
                    .getOrNull()
            }
            ?: emptyList()
    }

    suspend fun get(id: String): Recipe? = withContext(Dispatchers.IO) {
        val f = File(Paths.dishesDir(ctx), "$id.json")
        if (!f.exists()) null else runCatching { json.decodeFromString<Recipe>(f.readText()) }.getOrNull()
    }

    // ---------------------------------------------------------------- 写

    suspend fun upsert(recipe: Recipe): Recipe = withContext(Dispatchers.IO) {
        val file = File(Paths.dishesDir(ctx), "${recipe.id}.json")
        atomicWrite(file, json.encodeToString(Recipe.serializer(), recipe).toByteArray())
        recipe
    }

    /** 逻辑删除：写墓碑而不是删文件，避免各端图片被反复增删。 */
    suspend fun softDelete(id: String) = withContext(Dispatchers.IO) {
        val existing = get(id) ?: return@withContext
        upsert(existing.copy(deleted = true, updatedAt = System.currentTimeMillis()))
        Unit
    }

    suspend fun hardDelete(id: String) = withContext(Dispatchers.IO) {
        File(Paths.dishesDir(ctx), "$id.json").delete()
        Unit
    }

    // ---------------------------------------------------------------- 图片

    /**
     * 把一份字节流写入 images 目录，按内容散列命名。
     * @return 相对仓库根的路径，例如 `images/2026/03/a1b2c3d4.jpg`
     */
    suspend fun storeImage(bytes: ByteArray, extension: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val short = digest.take(8).joinToString("") { "%02x".format(it) }
        val cal = java.util.Calendar.getInstance()
        val year = "%04d".format(cal.get(java.util.Calendar.YEAR))
        val month = "%02d".format(cal.get(java.util.Calendar.MONTH) + 1)
        val dir = File(Paths.imagesDir(ctx), "$year/$month").apply { mkdirs() }
        val ext = extension.lowercase().removePrefix(".").ifBlank { "jpg" }
        val file = File(dir, "$short.$ext")
        if (!file.exists()) atomicWrite(file, bytes)
        "images/$year/$month/$short.$ext"
    }

    /** 仓库相对路径 -> 绝对文件 */
    fun resolve(relative: String): File? {
        if (relative.isBlank()) return null
        val f = File(Paths.repo(ctx), relative)
        return if (f.exists()) f else null
    }

    // ---------------------------------------------------------------- 工具

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun newId(): String = UUID.randomUUID().toString()
}
