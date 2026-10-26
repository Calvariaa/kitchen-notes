package io.github.Calvariaa.kitchennotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.data.MealSlot
import io.github.Calvariaa.kitchennotes.data.Menu
import io.github.Calvariaa.kitchennotes.data.Staple
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 菜单标题/日期的人话渲染。
 *
 * 放在 UI 层而不是 [Menu] 里，理由和整个项目一致的 i18n 约定：
 * 数据层产出字符串就会在切语言后冻结。这里全都是 Composable，
 * 读的是当前 Locale，语言一换立刻重算。
 */
@Composable
fun mealSlotLabel(slot: MealSlot): String = stringResource(slot.labelRes)

@Composable
fun stapleLabel(staple: Staple): String = stringResource(staple.labelRes)

/**
 * `2026-10-26` → 本地化的短日期。解析失败就原样返回，不崩。
 *
 * 不能用固定的 `MMM d` 交给不同 locale：中文的 `MMM` 本身就是「10月」，
 * 拼出来会变成「10月 26」这种倒装。中文得用 `M月d日`，英文才用 `MMM d`。
 */
@Composable
fun formatMenuDate(date: String): String {
    val locale = localeForCurrentLanguage()
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val pattern = if (locale.language == "zh") "M月d日" else "MMM d"
    return parsed.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** 带年份的完整日期，用于「做过的记录」这类跨年的场合 */
@Composable
fun formatFullDate(date: String): String {
    val locale = localeForCurrentLanguage()
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val pattern = if (locale.language == "zh") "yyyy年M月d日" else "MMM d, yyyy"
    return parsed.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** 毫秒时间戳 → 短格式，评分时间用 */
@Composable
fun formatTimestamp(ms: Long): String {
    val locale = localeForCurrentLanguage()
    val dt = java.time.Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    val pattern = if (locale.language == "zh") "M月d日 HH:mm" else "MMM d HH:mm"
    return dt.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * 取当前界面语言的 Locale。
 *
 * 不能直接用 `Locale.getDefault()`：App 的语言是设置项（可能和系统不同），
 * [ProvideAppLocale] 替换的是 Compose 的 LocalContext，不改进程默认 Locale。
 */
@Composable
private fun localeForCurrentLanguage(): Locale {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tag = ctx.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
    return runCatching { Locale.forLanguageTag(tag) }
        .getOrNull()
        ?.takeIf { it.language.isNotBlank() }
        ?: Locale.getDefault()
}

/** 菜单标题：「10月26日 · 早餐」。日期与餐次都是格式化的，不存储 */
@Composable
fun menuTitle(menu: Menu): String = stringResource(
    R.string.menu_title_format,
    formatMenuDate(menu.date),
    mealSlotLabel(menu.slot),
)

/**
 * 菜单简介：主食 + 菜名。
 * 主食在前是因为点开菜单先想知道「配什么饭」，再看具体菜。
 * 两边都空时返回「还没勾菜」，避免出现一个空字符串的灰条。
 */
@Composable
fun menuSummaryParts(menu: Menu): MenuSummary {
    val staples = menu.staples.map { stringResource(it.labelRes) }
    val dishes = menu.items.map { it.title.ifBlank { "—" } }
    val parts = buildList {
        if (staples.isNotEmpty()) add(staples.joinToString(" / "))
        addAll(dishes)
    }
    return MenuSummary(staples = staples, dishes = dishes, joined = parts.joinToString(" · "))
}

data class MenuSummary(
    val staples: List<String>,
    val dishes: List<String>,
    val joined: String,
)
