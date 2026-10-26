package io.github.Calvariaa.kitchennotes.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一餐（早餐/午餐/晚餐）。
 *
 * 为什么单独存成实体而不是给菜谱加个「哪顿」字段：
 * 一次吃饭是多道菜的组合，而且「这顿饭做过什么、给每道菜打几分」是独立的记录，
 * 不该回写到菜谱本身。菜谱是模板，菜单是使用记录。
 */
@Serializable
enum class MealSlot {
    @SerialName("breakfast") BREAKFAST,
    @SerialName("lunch") LUNCH,
    @SerialName("dinner") DINNER,
    ;

    /** 用于生成标题的字符串资源 id（数据层不拼人话，只给 id） */
    val labelRes: Int
        get() = when (this) {
            BREAKFAST -> io.github.Calvariaa.kitchennotes.R.string.meal_breakfast
            LUNCH -> io.github.Calvariaa.kitchennotes.R.string.meal_lunch
            DINNER -> io.github.Calvariaa.kitchennotes.R.string.meal_dinner
        }
}

/**
 * 主食。存枚举而不是自由文本，这样切语言时「米饭/rice」跟着变，
 * 而不是把某个人的输入冻结成数据漏到另一种语言的界面上。
 */
@Serializable
enum class Staple {
    @SerialName("rice") RICE,
    @SerialName("noodle") NOODLE,
    @SerialName("bun") BUN,
    ;

    val labelRes: Int
        get() = when (this) {
            RICE -> io.github.Calvariaa.kitchennotes.R.string.staple_rice
            NOODLE -> io.github.Calvariaa.kitchennotes.R.string.staple_noodle
            BUN -> io.github.Calvariaa.kitchennotes.R.string.staple_bun
        }
}

/**
 * 菜单里的一道菜。
 *
 * - [title] 是冗余快照，菜谱被逻辑删除后菜单仍要能显示当时吃的是什么。
 * - [rating] 0 表示没打分（用户明确说「也可以不打」），1..5 星。
 * - [ratedAt] 打分时刻。没打分时为 null —— 单独一个时间字段而不是复用 updatedAt，
 *   因为改菜单结构不该篡改「我什么时候给的这分」。
 */
@Serializable
data class MenuItem(
    @SerialName("recipeId") val recipeId: String,
    @SerialName("title") val title: String = "",
    @SerialName("rating") val rating: Int = 0,
    @SerialName("ratedAt") val ratedAt: Long? = null,
) {
    val rated: Boolean get() = rating > 0
}

/**
 * 一顿饭的记录。一餐一个 JSON 文件，和菜谱一样按 id 落盘，能被 git 跟踪合并。
 *
 * 标题与简介**不存储**：标题由 [date] + [slot] 拼（「10月26日 早餐」），
 * 简介由主食 + 菜名拼。存下来就会在切语言后变成冻结的中文。
 */
@Serializable
data class Menu(
    @SerialName("id") val id: String,
    /** 日期 `yyyy-MM-dd`（本地时区），排序和标题都基于它 */
    @SerialName("date") val date: String,
    @SerialName("slot") val slot: MealSlot = MealSlot.DINNER,
    @SerialName("staples") val staples: List<Staple> = emptyList(),
    @SerialName("items") val items: List<MenuItem> = emptyList(),
    /** 备注，可空 */
    @SerialName("note") val note: String = "",
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    @SerialName("deleted") val deleted: Boolean = false,
) {
    /** 已打分的条目平均分；没有评分返回 null（UI 显示「未评分」而不是 0 分） */
    fun averageRating(): Double? {
        val rated = items.filter { it.rated }
        if (rated.isEmpty()) return null
        return rated.sumOf { it.rating }.toDouble() / rated.size
    }

    fun withItemRating(recipeId: String, rating: Int, now: Long): Menu = copy(
        items = items.map { item ->
            if (item.recipeId != recipeId) item
            // 打分归零等于取消评分，时间戳一并清掉
            else item.copy(rating = rating.coerceIn(0, 5), ratedAt = if (rating > 0) now else null)
        },
        updatedAt = now,
    )
}

/** 烹饪历史里的一条：某天某餐做过这道菜，以及当时的评分 */
data class CookedRecord(
    val menuId: String,
    val date: String,
    val slot: MealSlot,
    val rating: Int,
    val ratedAt: Long?,
)

/** 菜谱列表排序方式 */
enum class RecipeSort(val labelRes: Int) {
    /** 最近更新优先（默认，和改动节奏一致） */
    UPDATED(io.github.Calvariaa.kitchennotes.R.string.sort_updated),
    /** 新加的在前 */
    CREATED(io.github.Calvariaa.kitchennotes.R.string.sort_created),
    /** 按名字首字母/拼音 */
    TITLE(io.github.Calvariaa.kitchennotes.R.string.sort_title),
    /** 难度从易到难 */
    DIFFICULTY(io.github.Calvariaa.kitchennotes.R.string.sort_difficulty),
    /** 按最近一次做的日期倒序，没做过的排最后 */
    LAST_COOKED(io.github.Calvariaa.kitchennotes.R.string.sort_last_cooked),
}
