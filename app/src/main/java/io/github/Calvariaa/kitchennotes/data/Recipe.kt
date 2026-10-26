package io.github.Calvariaa.kitchennotes.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一道菜的制作配料。
 *
 * 名称、用量分开存储，方便卡片只显示名称、详情页显示完整用量。
 * [amount] 允许为空串（例如「盐 适量」其实也可以写 适量；为空表示原图没给用量）。
 */
@Serializable
data class Ingredient(
    @SerialName("name") val name: String,
    @SerialName("amount") val amount: String = "",
) {
    /** 卡片上的一行展示文本，例如 `猪里脊 300g` */
    val display: String
        get() = if (amount.isBlank()) name else "$name $amount"
}

/**
 * 一个制作步骤。
 *
 * @param index 从 1 开始，AI 生成时就让它排好序，便于详情页直接渲染。
 * @param title 步骤短标题，例如「腌制里脊」，可为空。
 * @param detail 详细做法，要求写到可执行粒度。
 * @param durationMinutes 该步预估耗时（分钟），AI 推算，允许为 null。
 * @param heat 火候提示，例如「大火」「中小火 3 分钟」，允许为 null。
 */
@Serializable
data class Step(
    @SerialName("index") val index: Int,
    @SerialName("title") val title: String = "",
    @SerialName("detail") val detail: String,
    @SerialName("durationMinutes") val durationMinutes: Int? = null,
    @SerialName("heat") val heat: String? = null,
)

/**
 * 一条菜谱。整条记录是一个 JSON 文件，会被 git 跟踪与同步。
 *
 * 设计要点：
 * - [id] 是 UUID，永远不变，作为文件名与跨端主键。
 * - [updatedAt] 是毫秒时间戳，git 合并出现冲突时用它做「取新」仲裁。
 * - 图片只存相对路径（指向 assets 目录），git 里二进制只增不删（见 SyncService 的墓碑策略）。
 */
@Serializable
data class Recipe(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("summary") val summary: String = "",
    /** 分类标签，例如「家常菜」「川菜」「快手菜」 */
    @SerialName("tags") val tags: List<String> = emptyList(),
    /** 份量描述，例如「2 人份」 */
    @SerialName("serving") val serving: String = "",
    /** 总耗时（分钟） */
    @SerialName("totalMinutes") val totalMinutes: Int? = null,
    /** 难度 1-5 */
    @SerialName("difficulty") val difficulty: Int = 3,
    @SerialName("ingredients") val ingredients: List<Ingredient> = emptyList(),
    @SerialName("steps") val steps: List<Step> = emptyList(),
    /** 小贴士 */
    @SerialName("tips") val tips: List<String> = emptyList(),
    /** 主图相对路径，例如 `images/2026/03/ab12.jpg`；空串表示暂无图 */
    @SerialName("coverImage") val coverImage: String = "",
    /** 其余图片（用户上传的原图 / AI 生成图） */
    @SerialName("images") val images: List<String> = emptyList(),
    /** 用户上传时附带的原始文字，保留以便日后重新生成 */
    @SerialName("sourceText") val sourceText: String = "",
    /** 来源描述，例如「小红书截图」「自己拍的」 */
    @SerialName("sourcePlatform") val sourcePlatform: String = "",
    /** 创建者显示名 */
    @SerialName("author") val author: String = "",
    /** 创建者邮箱，用于同步时辨识 */
    @SerialName("authorEmail") val authorEmail: String = "",
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    /** 逻辑删除标记，跨端删除靠它而非真的删文件，避免图片被 git 反复增删 */
    @SerialName("deleted") val deleted: Boolean = false,
) {
    /**
     * 材料摘要的**纯数据**部分：前 [maxItems] 个材料名，以及剩余数量。
     *
     * 故意不返回拼好的句子 —— 「等 N 种」「暂无材料」要按界面语言渲染，
     * 数据层拼出来的中文会在英文界面上漏出来。UI 侧用 card_more_ingredients 组装。
     */
    fun ingredientPreviewParts(maxItems: Int = 6): Pair<String, Int> {
        val head = ingredients.take(maxItems).joinToString(" · ") { it.name }
        return head to (ingredients.size - maxItems).coerceAtLeast(0)
    }

    fun searchBlob(): String = buildString {
        append(title).append(' ')
        append(summary).append(' ')
        append(tags.joinToString(" ")).append(' ')
        append(ingredients.joinToString(" ") { it.display })
    }.lowercase()
}
