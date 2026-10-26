package io.github.Calvariaa.kitchennotes.ai

import io.github.Calvariaa.kitchennotes.data.Ingredient
import io.github.Calvariaa.kitchennotes.data.Recipe
import io.github.Calvariaa.kitchennotes.data.Step
import io.github.Calvariaa.kitchennotes.ai.RecipeJsonRepair.intOr
import io.github.Calvariaa.kitchennotes.ai.RecipeJsonRepair.str
import io.github.Calvariaa.kitchennotes.ai.RecipeJsonRepair.stringList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 把模型输出的 JSON 转成领域对象。
 *
 * 这一层存在的理由：模型偶尔会用错字段名（`materials` / `ingredients`）、
 * 会把 steps 写成纯字符串数组、会给 difficulty 写「中等」。
 * 与其在提示词里反复哀求，不如在解析层做一次宽容映射 —— 让输出永远能落成一张卡片。
 */
object RecipeAssembler {

    fun toRecipe(
        obj: JsonObject,
        id: String,
        coverImage: String,
        images: List<String>,
        sourceText: String,
        sourcePlatform: String,
        author: String,
        authorEmail: String,
        now: Long = System.currentTimeMillis(),
    ): Recipe {
        val title = pickStr(obj, "title", "name", "dish_name", "菜名", "名称").ifBlank { "未命名菜品" }
        val summary = pickStr(obj, "summary", "description", "简介", "介绍")
        val tags = pickList(obj, "tags", "categories", "标签", "分类").take(6)
        val serving = pickStr(obj, "serving", "servings", "portion", "份量", "份量参考")
        val total = pick(obj, "totalMinutes", "total_minutes", "time", "总耗时").intOr(null)
        val difficulty = pick(obj, "difficulty", "难度").let { el ->
            val direct = el.intOr(null)
            if (direct != null) direct.coerceIn(1, 5)
            else when (el.str()) {
                "很容易", "简单", "入门", "新手", "easy", "Easy" -> 1
                "较简单", "容易" -> 2
                "中等", "一般", "普通", "medium", "Medium" -> 3
                "较难", "有难度" -> 4
                "困难", "很难", "hard", "Hard", "大师" -> 5
                else -> 3
            }
        }

        val ingredients = parseIngredients(obj)
        val steps = parseSteps(obj)
        val tips = pickList(obj, "tips", "notes", "小贴士", "提示", "注意事项").take(6)

        return Recipe(
            id = id,
            title = title.take(40),
            summary = summary.take(200),
            tags = tags,
            serving = serving,
            totalMinutes = total ?: steps.sumOf { it.durationMinutes ?: 0 }.takeIf { it > 0 },
            difficulty = difficulty,
            ingredients = ingredients,
            steps = steps,
            tips = tips,
            coverImage = coverImage,
            images = images,
            sourceText = sourceText,
            sourcePlatform = sourcePlatform,
            author = author,
            authorEmail = authorEmail,
            createdAt = now,
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------ 材料

    private fun parseIngredients(root: JsonObject): List<Ingredient> {
        val el = pick(root, "ingredients", "materials", "ingredient", "材料", "食材", "原料")
        val raw: List<Pair<String, String>> = when (el) {
            is JsonArray -> el.jsonArray.mapNotNull { item ->
                when (item) {
                    is JsonObject -> {
                        val name = pickStr(item, "name", "item", "ingredient", "名称", "材料")
                        val amount = pickStr(item, "amount", "quantity", "qty", "用量", "数量", "分量")
                        if (name.isBlank()) null else name.trim() to amount.trim()
                    }

                    is JsonPrimitive -> splitNameAmount(item.content)
                    else -> null
                }
            }

            is JsonPrimitive -> el.content.lines()
                .map { it.trim().trimStart('-', '*', '·', '•').trim() }
                .filter { it.isNotBlank() }
                .map { splitNameAmount(it) }

            else -> emptyList()
        }

        return raw
            .map { (n, a) -> Ingredient(name = cleanName(n), amount = cleanAmount(a)) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
            .take(40)
    }

    /** "猪里脊 300g" / "猪里脊：300g" / "300g 猪里脊" */
    private fun splitNameAmount(line: String): Pair<String, String> {
        val separators = listOf("：", ":", "——", "—", "　", " ", "\t")
        for (sep in separators) {
            val idx = line.indexOf(sep)
            if (idx > 0) {
                val head = line.substring(0, idx).trim()
                val tail = line.substring(idx + sep.length).trim()
                if (head.matches(AMOUNT_LIKE)) return tail to head
                if (head.isNotBlank() && tail.isNotBlank()) return head to tail
            }
        }
        // 没有分隔符：尝试把末尾的量词拆出来
        val m = Regex("^(.+?)\\s*(\\d[\\d./]*\\s*(?:g|kg|ml|l|克|千克|毫升|升|个|根|瓣|片|颗|只|条|张|把|汤匙|茶匙|勺|杯|小勺|大勺|斤|两)·?)$")
            .find(line.trim())
        if (m != null) return m.groupValues[1].trim() to m.groupValues[2].trim()
        return line to ""
    }

    private val AMOUNT_LIKE = Regex("^[\\d０-９][\\d０-９./、\\-~\\s]*(?:g|kg|ml|l|克|千克|毫升|升|个|根|瓣|片|颗|只|条|张|把|汤匙|茶匙|勺|杯|斤|两)?$")

    private fun cleanName(s: String): String = s.trim()
        .removeSurrounding("\"")
        .replace(Regex("^[•·\\-*\\d.、]\\s*"), "")
        .take(24)

    private fun cleanAmount(s: String): String = s.trim()
        .removeSurrounding("\"")
        .replace("约", "约 ")
        .replace(Regex("\\s+"), " ")
        .take(30)

    // ------------------------------------------------------------ 步骤

    private fun parseSteps(root: JsonObject): List<Step> {
        val el = pick(root, "steps", "instructions", "directions", "procedure", "步骤", "做法")
        val raw: List<Step> = when (el) {
            is JsonArray -> el.jsonArray.mapIndexedNotNull { i, item ->
                when (item) {
                    is JsonObject -> {
                        val detail = pickStr(item, "detail", "description", "text", "content", "说明", "做法")
                            .ifBlank { pickStr(item, "step", "步骤") }
                        if (detail.isBlank()) return@mapIndexedNotNull null
                        Step(
                            index = pick(item, "index", "step", "order", "序号").intOr(i + 1) ?: (i + 1),
                            title = pickStr(item, "title", "name", "标题").trim().take(20),
                            detail = detail.trim(),
                            durationMinutes = pick(
                                item, "durationMinutes", "duration_minutes", "minutes", "time", "时长", "耗时",
                            ).intOr(null)?.takeIf { it in 0..1440 },
                            heat = pickStr(item, "heat", "fire", "火候").trim().ifBlank { null },
                        )
                    }

                    is JsonPrimitive -> {
                        val t = item.content.trim()
                        if (t.isBlank()) null else Step(index = i + 1, detail = t)
                    }

                    else -> null
                }
            }

            is JsonPrimitive -> el.content.lines()
                .map { it.trim().trimStart('-', '*', '·', '•').trim() }
                .filter { it.isNotBlank() }
                .mapIndexed { i, s ->
                    Step(
                        index = i + 1,
                        detail = s.replace(Regex("^\\d+[.、)]\\s*"), ""),
                    )
                }

            else -> emptyList()
        }

        // 重排 index 保证从 1 连续，详情页的「第 N 步」才不会跳号
        return raw.sortedBy { it.index }
            .mapIndexed { i, s -> s.copy(index = i + 1) }
            .take(30)
    }

    // ------------------------------------------------------------ 取值小工具

    private fun pick(obj: JsonObject, vararg keys: String): kotlinx.serialization.json.JsonElement? {
        for (k in keys) {
            obj[k]?.let { return it }
        }
        // 大小写不敏感 + 忽略下划线/空格兜底
        val normalized = keys.map { it.lowercase().replace("_", "").replace(" ", "") }.toSet()
        for ((k, v) in obj) {
            val nk = k.lowercase().replace("_", "").replace(" ", "")
            if (nk in normalized) return v
        }
        return null
    }

    private fun pickStr(obj: JsonObject, vararg keys: String): String =
        pick(obj, *keys).str()

    private fun pickList(obj: JsonObject, vararg keys: String): List<String> =
        pick(obj, *keys).stringList()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 40 }
}
