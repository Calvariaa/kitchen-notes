package io.github.Calvariaa.kitchennotes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 菜单仓库。布局与菜谱一致，只是换了个目录：
 *
 * ```
 * repo/
 *   dishes/<id>.json   菜谱模板
 *   menus/<id>.json    一顿饭的记录（含打分）
 * ```
 *
 * 为什么同样一餐一文件：和菜谱完全相同的理由 —— git 的合并粒度是文件。
 * 两个人同一天各记一顿晚饭，落成两个文件就不会冲突；写进一个 `menus.json`
 * 则每次都要整文件冲突仲裁。
 */
class MenuRepository(private val ctx: Context) {

    private val json = sharedJson

    private fun dir(): File = File(Paths.repo(ctx), "menus").apply { mkdirs() }

    // ---------------------------------------------------------------- 读

    suspend fun all(): List<Menu> = withContext(Dispatchers.IO) {
        dir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<Menu>(f.readText()) }.getOrNull()
            }
            ?.filter { !it.deleted }
            // 日期倒序（最近的饭在前），同日按餐次倒序：晚餐 > 午餐 > 早餐
            ?.sortedWith(compareByDescending<Menu> { it.date }.thenByDescending { it.slot.ordinal })
            ?: emptyList()
    }

    suspend fun get(id: String): Menu? = withContext(Dispatchers.IO) {
        val f = File(dir(), "$id.json")
        if (!f.exists()) null
        else runCatching { json.decodeFromString<Menu>(f.readText()) }.getOrNull()
    }

    // ---------------------------------------------------------------- 写

    suspend fun upsert(menu: Menu): Menu = withContext(Dispatchers.IO) {
        atomicWrite(File(dir(), "${menu.id}.json"), json.encodeToString(Menu.serializer(), menu).toByteArray())
        menu
    }

    suspend fun softDelete(id: String) = withContext(Dispatchers.IO) {
        val existing = get(id) ?: return@withContext
        upsert(existing.copy(deleted = true, updatedAt = System.currentTimeMillis()))
        Unit
    }

    /**
     * 某道菜被做过的全部记录，按日期倒序。
     *
     * 从菜单文件反查而不是在菜谱里存一份历史：菜谱的 JSON 是被高频编辑的对象，
     * 往里塞一份会随同步不断膨胀的历史数组，冲突面积也变大。菜单已经是事实来源，
     * 反查一遍就是 O(菜单数)，这个量级下没有性能问题。
     */
    suspend fun cookedRecordsFor(recipeId: String): List<CookedRecord> = withContext(Dispatchers.IO) {
        all()
            .mapNotNull { menu ->
                val item = menu.items.firstOrNull { it.recipeId == recipeId } ?: return@mapNotNull null
                CookedRecord(menu.id, menu.date, menu.slot, item.rating, item.ratedAt)
            }
            .sortedByDescending { it.date }
    }

    /** 全部菜谱最近一次被做的日期（recipeId -> yyyy-MM-dd），列表排序用 */
    suspend fun lastCookedMap(): Map<String, String> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, String>()
        all().forEach { menu ->
            menu.items.forEach { item ->
                // 同一个 recipeId 可能在多顿饭里出现，取日期最大的那次
                val prev = out[item.recipeId]
                if (prev == null || menu.date > prev) out[item.recipeId] = menu.date
            }
        }
        out
    }

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
