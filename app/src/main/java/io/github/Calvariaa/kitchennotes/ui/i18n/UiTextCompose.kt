package io.github.Calvariaa.kitchennotes.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.Calvariaa.kitchennotes.ui.i18n.Msg.generatingCompress

/**
 * 在 Compose 里解析一条 [UiText]。
 *
 * 直接读 `LocalContext.current.resources`：因为 [ProvideAppLocale] 已经把这个 Context
 * 换成目标语言的，所以这里拿到的就是当前界面语言，不用再传 language 参数。
 */
@Composable
fun UiText.text(): String {
    val res = LocalContext.current.resources
    return remember(this, res) { resolve(res) }
}

/** 生成阶段文案：带张数的那个要格式化，单独包一层好读 */
@Composable
fun stageCompressText(count: Int): String {
    val res = LocalContext.current.resources
    return remember(count, res) { res.getString(generatingCompress, count) }
}
