package io.github.Calvariaa.kitchennotes.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 冲突标记的解析与仲裁。
 *
 * **为什么单独一个文件**：这段逻辑原先内联在 `GitRepo` 里，假设「冲突标记包裹整个
 * JSON 文件」，而 git 给出的实际形态是**行内标记**：
 *
 * ```
 * {
 *   "id": "seed",
 * <<<<<<< HEAD
 *   "note": "B 改的",
 *   "updatedAt": 6000,
 * =======
 *   "note": "A 改的",
 *   "updatedAt": 5000,
 * >>>>>>> 99b0241
 *   "deleted": false
 * }
 * ```
 *
 * 大括号在标记**外面**。原实现取两段文本后要求 `startsWith("{")`，于是两边都判成
 * 「解析失败」，`winner = null`，**谁都不写回** —— 文件就这么永远停在冲突态。
 * 之后 `RecipeRepository.all()` 的 `runCatching` 把它吞掉，UI 上表现为
 * **菜谱/菜单凭空消失，而且两台设备一起消失**（因为带标记的文件被提交并推送出去了）。
 *
 * 实测复现：`_mesh_repro/MeshRepro5.java`，两边各改同一道菜 → 两台都变成
 * 「带冲突标记(解析会失败)」。
 *
 * 正确做法：把文件按行切成「公共前缀 / ours / theirs / 公共后缀」四段，
 * 分别和 ours、theirs 拼成两个完整 JSON，再各自解析、比较 `updatedAt`。
 */
internal object ConflictResolver {

    const val CONFLICT_START = "<<<<<<<"

    private const val OURS = CONFLICT_START
    private const val SEP = "======="
    private const val THEIRS = ">>>>>>>"

    /**
     * 文件被切成一段段「公共内容」或「冲突块」。
     *
     * **必须保留顺序**：一份文件里可能有多处冲突块，块与块之间还有公共行
     * （`"mid": 0,` 这种）。早期版本把第一个块之后的**所有**剩余行都当成
     * 尾部公共内容，于是第二处冲突被吞进 suffix，两侧文本里都残留着 `<<<<<<<`，
     * 解析必然失败 —— 表现为「多块冲突时文件永远修不好」。
     */
    private class Split(val segs: List<Pair<String, String>>) {
        fun side(ours: Boolean): String = buildString {
            segs.forEach { (o, t) -> append(if (ours) o else t) }
        }
    }

    /** 文件里有没有冲突标记。只看整行以标记开头的，避免正文里出现的巧合 */
    fun hasConflict(text: String): Boolean =
        text.lineSequence().any { it.startsWith(OURS) }

    /**
     * 按行解析冲突块。git 的标记永远独占一行，所以按行处理是安全的；
     * 逐个字符找 `=======` 会把正文里的等号也算进去。
     */
    private fun split(text: String): Split? {
        val lines = text.lines()
        val segs = mutableListOf<Pair<String, String>>()
        // 当前累积的公共段（两侧相同）
        val common = StringBuilder()
        var sawBlock = false
        var i = 0

        fun flushCommon() {
            if (common.isNotEmpty()) {
                val c = common.toString()
                segs += c to c
                common.setLength(0)
            }
        }

        while (i < lines.size) {
            if (!lines[i].startsWith(OURS)) {
                common.append(lines[i]).append('\n'); i++
                continue
            }
            flushCommon()
            sawBlock = true
            i++ // <<<<<<< 行
            val ours = StringBuilder()
            while (i < lines.size && !lines[i].startsWith(SEP) && !lines[i].startsWith(OURS)) {
                ours.append(lines[i]).append('\n'); i++
            }
            if (i >= lines.size || !lines[i].startsWith(SEP)) return null
            i++ // ======= 行
            val theirs = StringBuilder()
            while (i < lines.size && !lines[i].startsWith(THEIRS) && !lines[i].startsWith(OURS)) {
                theirs.append(lines[i]).append('\n'); i++
            }
            if (i >= lines.size || !lines[i].startsWith(THEIRS)) return null
            i++ // >>>>>>> 行
            segs += ours.toString() to theirs.toString()
        }
        flushCommon()
        // 标记一对都不成对（没走到上面）时不算冲突文件
        return if (sawBlock) Split(segs) else null
    }

    /**
     * 从文本里读出 `updatedAt`。
     *
     * 用 JSON 解析而不是正则找数字：一份文件里可能有多处数字字段，
     * 而仲裁的唯一依据必须是**语义上的** `updatedAt`。
     *
     * **必须先去掉尾随逗号。** 冲突块经常落在**最后一个字段**上：
     * ```
     * { "a": 1,
     * <<<<<<< HEAD
     *   "updatedAt": 100,
     * =======
     *   "updatedAt": 200,
     * >>>>>>> x
     * }
     * ```
     * 拼出来是 `{"a":1,"updatedAt":100,}` —— 严格 JSON 里这是语法错误，
     * 两侧都会解析失败，仲裁退化成「都不写」，文件又回到永久冲突态。
     * 现实里 git 生成的冲突文本本来就可能是这种形态，所以这里必须容错。
     */
    private fun updatedAtOf(json: String): Long? = runCatching {
        val cleaned = TRAILING_COMMA.replace(json, "$1")
        val obj = Json.parseToJsonElement(cleaned) as JsonObject
        (obj["updatedAt"] as? JsonPrimitive)?.longOrNull
    }.getOrNull()

    /** 逗号后面只剩空白再跟 `}` 或 `]` —— 标准 JSON 不允许 */
    private val TRAILING_COMMA = Regex(""",(\s*[}\]])""")

    /**
     * 打平时的确定性裁决。
     *
     * **为什么必须有**：`updatedAt` 相同（同一毫秒改的、或者一边从另一边原样拷过来）时，
     * 两边设备会各自选出不同的赢家：
     * ```
     * A 设备：ours = A 的版本，theirs = B 的版本 → 保留 ours（A 的）
     * B 设备：ours = B 的版本，theirs = A 的版本 → 保留 ours（B 的）
     * ```
     * 于是 A 和 B 各自持有一份不同的结果，互相推送、互相判冲突、互相再选错一次 ——
     * **永远不收敛**。这不是理论风险：`arbitrate` 旧写法是 `oursAt >= theirsAt`，
     * `mergeThreeWay` 是 `theirsAt >= oursAt`，都在打平时固定偏向一侧，
     * 而「一侧」在两台设备上指的又不是同一份数据。
     *
     * **为什么用提交 SHA 而不是 nodeId**：仲裁发生在两台设备的**同一次合并**里，
     * 双方看到的正是同样两个提交，只是 ours/theirs 的角色互换。SHA 是这两个提交的固有属性，
     * 天然满足「两台设备看到同一个值」；nodeId 做不到 —— 记录里根本没存「这条是谁改的」
     * （`author` 是用户填的显示名，可能为空或重名），要为它加一遍全链路传参不值得。
     *
     * 注意：这只保证**收敛**，不保证选对。真并发时两份改动因果平权，没有「更对」的一方，
     * 只需要「两台设备算出同一个答案」。选错的代价由 `conflicts/` 备份兜底。
     *
     * @return true 表示 ours 胜出
     */
    private fun oursWinsTie(
        oursKey: String?,
        theirsKey: String?,
        oursRaw: String,
        theirsRaw: String,
    ): Boolean {
        // 优先比提交 SHA：字典序大的赢。两台设备拿到的是同一对 SHA，结论必然一致。
        if (oursKey != null && theirsKey != null && oursKey != theirsKey) {
            return oursKey > theirsKey
        }
        // 拿不到提交（空仓库、手工调用）时退一步比正文：
        // 两个候选文本在两边设备上是同一对，比较结果同样可复现。
        return oursRaw >= theirsRaw
    }

    /**
     * 仲裁一份带冲突标记的文件。
     *
     * @param oursKey   本机侧的提交标识（`ObjectId.name()`），用于打平时定序
     * @param theirsKey 对端侧的提交标识
     * @return 选中的完整 JSON 文本；无法仲裁时返回 null（调用方必须保留原文件，
     *         绝不能像旧实现那样「两边都失败就什么都不写」然后照样提交）
     */
    fun arbitrate(text: String, oursKey: String? = null, theirsKey: String? = null): Resolution? {
        val split = split(text) ?: return null
        val oursRaw = split.side(true)
        val theirsRaw = split.side(false)
        val oursAt = updatedAtOf(oursRaw)
        val theirsAt = updatedAtOf(theirsRaw)

        // 只有一边能解析（另一边坏了）→ 用能解析的那边，这比丢数据强
        val pick: Pair<String, Boolean> = when {
            oursAt == null && theirsAt == null -> return null
            oursAt == null -> theirsRaw to false
            theirsAt == null -> oursRaw to true
            // 打平走确定性裁决，别再固定偏向 ours（会让两台设备选出不同结果）
            oursAt == theirsAt -> oursRaw to oursWinsTie(oursKey, theirsKey, oursRaw, theirsRaw)
            oursAt > theirsAt -> oursRaw to true
            else -> theirsRaw to false
        }
        val chosenAt = if (pick.second) oursAt!! else theirsAt!!
        // 写回前把尾随逗号清掉：`RecipeRepository.all()` / `MenuRepository.all()`
        // 用的是严格反序列化，留个 `,}` 就又变成「解析失败 → runCatching 吞掉 → 记录消失」。
        // 仲裁的全部意义是让这份文件重新可读，所以输出必须是合法 JSON。
        val cleaned = TRAILING_COMMA.replace(pick.first, "$1")
        return Resolution(
            text = cleaned,
            updatedAt = chosenAt,
            oursKept = pick.second,
            winnerRaw = pick.first,
            loserRaw = if (pick.second) theirsRaw else oursRaw,
            oursRaw = oursRaw,
            theirsRaw = theirsRaw,
        )
    }

    class Resolution(
        /** 应该写回文件的完整 JSON */
        val text: String,
        val updatedAt: Long,
        /** ours 是否胜出（落败的那份进冲突备份） */
        val oursKept: Boolean,
        /** 胜出方的原始文本（未清尾随逗号），供三方合并比对 */
        val winnerRaw: String = text,
        /** 落败方的原始文本，**必须落盘备份**，否则用户没有恢复途径 */
        val loserRaw: String = "",
        val oursRaw: String = text,
        val theirsRaw: String = text,
    )

    // ================================================================
    // 字段级三方合并
    // ================================================================

    /**
     * 字段级三方合并的结果。
     *
     * @param text     合并后的完整 JSON（[merged] 为 false 时仍是一份合法的降级结果）
     * @param conflicts 两侧改到同一字段且值不同的字段名
     * @param autoMerged 只有一边改过、被自动采纳的字段名
     * @param merged   true = 所有改动都保住了，两份改动都能上网
     */
    class ThreeWay(
        val text: String,
        val conflicts: List<String>,
        val autoMerged: List<String>,
        val merged: Boolean,
    )

    /**
     * 三方合并（ours / base / theirs）纯 JSON 对象实现。
     *
     * **`base` 不能省。** 只比较两份文本（two-way）无法区分「一边没动」和「两边都改成一样」：
     * ```
     * base   : summary = "经典做法"
     * ours   : summary = "A 补充的要点"    ← 只有 A 改了
     * theirs : summary = "经典做法"        ← B 根本没动这个字段
     * ```
     * two-way 看到 `ours != theirs` 就当成冲突，把 A 的改动判成输家丢掉 ——
     * 这正是「离线各改一半、连线后一半消失」的成因。有了 base 才能判定：
     * `theirs == base` ⇒ 只有我改了，直接用我的。
     *
     * 逐字段规则：
     *  - `ours == base` → 采纳 theirs（只有对方改）
     *  - `theirs == base` → 采纳 ours（只有我改）
     *  - `ours == theirs` → 原样保留
     *  - 三者互不相同 → **真冲突**，按 `updatedAt` 取较新，字段名记进 [ThreeWay.conflicts]
     *  - base 未知（文件在 base 里不存在）→ 退化成「两边都算改过」的保守判断
     */
    fun mergeThreeWay(
        oursRaw: String,
        baseRaw: String?,
        theirsRaw: String,
        oursKey: String? = null,
        theirsKey: String? = null,
    ): ThreeWay? {
        val ours = parseObject(oursRaw) ?: return null
        val theirs = parseObject(theirsRaw) ?: return null
        val base = baseRaw?.let { parseObject(it) }
        val oursAt = updatedAtOfObj(ours) ?: 0L
        val theirsAt = updatedAtOfObj(theirs) ?: 0L
        // 打平必须走确定性裁决：写死 `>=` 会让两台设备各自保留自己那份，互相推不收敛
        val theirsNewer = if (theirsAt != oursAt) {
            theirsAt > oursAt
        } else {
            !oursWinsTie(oursKey, theirsKey, oursRaw, theirsRaw)
        }

        val conflicts = mutableListOf<String>()
        val autoMerged = mutableListOf<String>()
        val out = LinkedHashMap<String, JsonElement>()

        for (key in ours.keys + theirs.keys + (base?.keys ?: emptySet())) {
            if (key == "updatedAt") continue
            val o = ours[key]
            val t = theirs[key]
            val b = base?.get(key)
            when {
                o == t -> out[key] = o ?: t!!                      // 两边一样
                b != null && o == b -> {                            // 只有对方改了
                    out[key] = t!!; autoMerged += key
                }
                b != null && t == b -> {                            // 只有我改了
                    out[key] = o!!; autoMerged += key
                }
                o == null -> { out[key] = t!!; autoMerged += key }
                t == null -> { out[key] = o; autoMerged += key }
                else -> {
                    // 三者互不相同 → 真冲突，取较新那份，但记下来让用户知道
                    out[key] = if (theirsNewer) t else o
                    conflicts += key
                }
            }
        }
        // updatedAt 取较大值：合并结果比任何一侧都新，下一轮不会又被判成冲突
        val maxAt = maxOf(updatedAtOfObj(ours) ?: 0L, updatedAtOfObj(theirs) ?: 0L)
        out["updatedAt"] = JsonPrimitive(maxAt)

        val text = buildString {
            append("{\n")
            val names = out.keys.toList()
            names.forEachIndexed { i, k ->
                append("  ").append(JsonPrimitive(k).toString()).append(": ")
                append(out[k]!!.toString())
                if (i != names.size - 1) append(',')
                append('\n')
            }
            append("}\n")
        }
        return ThreeWay(
            text = text,
            conflicts = conflicts,
            autoMerged = autoMerged,
            merged = conflicts.isEmpty(),
        )
    }

    private fun parseObject(raw: String): JsonObject? = runCatching {
        Json.parseToJsonElement(TRAILING_COMMA.replace(raw, "$1")) as JsonObject
    }.getOrNull()

    private fun updatedAtOfObj(o: JsonObject): Long? =
        (o["updatedAt"] as? JsonPrimitive)?.longOrNull
}
