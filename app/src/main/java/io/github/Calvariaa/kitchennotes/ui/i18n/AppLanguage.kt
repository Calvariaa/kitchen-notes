package io.github.Calvariaa.kitchennotes.ui.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * 应用内语言切换。
 *
 * 为什么不用 `AppCompatDelegate.setApplicationLocales` + `AppCompatActivity`：
 * 那套要走 AppCompat 的 Activity 委托，而本项目是纯 ComponentActivity + Compose，
 * 为了一个语言开关把 Activity 基类换掉、再引 appcompat，代价不划算。
 *
 * 这里走更直接的一条路 —— 覆盖 `LocalContext` / `LocalConfiguration`：
 * Compose 的 `stringResource` 读的是 `LocalContext.current.resources`，
 * 我们塞一个换了 config 的 Context 进去，所有字符串立刻跟着变，
 * 而且切换不需要重建 Activity（用户在设置页点一下，全应用即时刷新）。
 *
 * 代价：`LocalConfiguration` 一旦被覆盖，Compose 就不再自动跟随系统语言/深色变化。
 * 所以 [systemLocaleOverride] 在「跟随系统」模式下返回 null，走原生路径不干预。
 */
object AppLanguage {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    /** 设置页下拉项的顺序与显示 */
    val selectable = listOf(SYSTEM, ZH, EN)

    fun toLocale(tag: String): Locale? = when (tag) {
        ZH -> Locale.SIMPLIFIED_CHINESE
        EN -> Locale.ENGLISH
        else -> null
    }

    fun displayName(tag: String, ctx: Context): String = when (tag) {
        ZH -> ctx.getString(io.github.Calvariaa.kitchennotes.R.string.settings_language_zh)
        EN -> ctx.getString(io.github.Calvariaa.kitchennotes.R.string.settings_language_en)
        else -> ctx.getString(io.github.Calvariaa.kitchennotes.R.string.settings_language_system)
    }
}

/**
 * 把 base 包成指定语言的 Context。
 *
 * @param language AppLanguage 的取值；SYSTEM 直接原样返回。
 */
fun localizedContext(base: Context, language: String): Context {
    val locale = AppLanguage.toLocale(language) ?: return base
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLocales(LocaleList(locale))
    return base.createConfigurationContext(config)
}

/**
 * 在子树里把 Context / Configuration 换成目标语言。
 * 只覆盖 Compose 的查找路径，不触碰 Application 全局状态。
 */
@Composable
fun ProvideAppLocale(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val locale = AppLanguage.toLocale(language)

    if (locale == null) {
        // 跟随系统：不接管，让 Compose 正常响应系统语言变更
        content()
        return
    }

    val ctx = remember(base, language) { localizedContext(base, language) }
    val config = remember(ctx) { ctx.resources.configuration }

    CompositionLocalProvider(
        LocalContext provides ctx,
        LocalConfiguration provides config,
        content = content,
    )
}

/**
 * 非 Compose 层（ViewModel 里的状态文案、同步提示）也用同一套字符串。
 *
 * ViewModel 拿不到 Compose 的 LocalContext，所以它持有一个 Context，
 * 每次要用字符串时用这个函数包一下 —— 保证和界面语言一致，
 * 不会出现「界面是英文、同步提示还是中文」的割裂。
 */
fun Context.strings(language: String): android.content.res.Resources =
    if (language == AppLanguage.SYSTEM) resources
    else localizedContext(this, language).resources

/** 供 API < 24 兼容用的占位（minSdk 26，这里只是表明意图） */
internal val supportsLocaleList: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
