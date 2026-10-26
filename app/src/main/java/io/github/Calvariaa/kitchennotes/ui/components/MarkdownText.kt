package io.github.Calvariaa.kitchennotes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * 一个只为 Release notes 服务的极简 Markdown 渲染器。
 *
 * 为什么不用现成库（Markwon / compose-markdown）：这两者都是几百 KB 的全能解析器，
 * 支持表格、脚注、内嵌 HTML、图片加载 —— 而这里要渲染的开源只有我们自己 CI 生成的
 * 那一小段：三级标题、无序列表、`**粗体**`、`` `代码` ``。为一个设置页里的折叠块
 * 引一个大依赖，代价和收益不成比例。
 *
 * 支持的语法（超出就原样当文本，不会崩）：
 *  - `### 标题` / `## ` / `# `     → 小标题
 *  - `- 项目` / `* 项目`           → 带点的列表项（支持一级缩进）
 *  - `**粗体**`
 *  - `` `代码` ``                  → 等宽 + 底色
 *  - `<!-- 注释 -->`               → 整段丢弃（我们的 CI 用它当追加锚点）
 *  - `[文字](链接)`                → 只保留文字（设置页里不可点，也没必要可点）
 *  - 裸 URL                        → 下划线样式
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = rememberBlocks(markdown)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = block.text.toAnnotated(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MdBlock.Bullet -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    // 用固定宽度的圆点列对齐，不用 "• " 前缀 —— 前缀在换行时会
                    // 让第二行的文字顶到圆点下面，视觉上参差
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(if (block.depth > 0) 24.dp else 12.dp),
                    )
                    Text(
                        text = block.text.toAnnotated(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = block.text.toAnnotated(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                MdBlock.Gap -> Unit
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Bullet(val text: String, val depth: Int) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Gap : MdBlock
}

/**
 * 解析成块级序列。
 *
 * `remember(markdown)` 而不是每次重组都重算：notes 是不变的字符串，
 * 但设置页会因为开关、进度条频繁重组，重复解析纯属浪费。
 */
@Composable
private fun rememberBlocks(markdown: String): List<MdBlock> =
    androidx.compose.runtime.remember(markdown) { parseBlocks(markdown) }

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    // CI 用 <!-- ... --> 做锚点；HTML 注释在纯文本视图里会露出尖括号，直接删
    val cleaned = src.replace(Regex("<!--[\\s\\S]*?-->"), "")

    for (raw in cleaned.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank()) {
            if (out.isNotEmpty() && out.last() !is MdBlock.Gap) out.add(MdBlock.Gap)
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        if (heading != null) {
            out.add(MdBlock.Heading(heading.groupValues[2]))
            continue
        }

        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").find(line)
        if (bullet != null) {
            // 每 2 个空格算一级缩进，最多认两级（我们的 notes 只有一级）
            val depth = (bullet.groupValues[1].length / 2).coerceAtMost(2)
            out.add(MdBlock.Bullet(bullet.groupValues[2], depth))
            continue
        }

        out.add(MdBlock.Paragraph(line))
    }

    // 去掉首尾的空行，避免设置页里出现无谓的顶部间距
    return out.dropWhile { it is MdBlock.Gap }.dropLastWhile { it is MdBlock.Gap }
}

/** 行内语法：粗体、代码、链接、裸 URL。递归处理粗体/代码内部的纯文本即可。 */
private fun String.toAnnotated(): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = this@toAnnotated
    while (i < s.length) {
        when {
            // **粗体**
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(s.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }

            // `代码`
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = androidx.compose.ui.graphics.Color(0x14000000),
                        ),
                    )
                    append(s.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }

            // [文字](链接) → 只留文字
            s[i] == '[' -> {
                val close = s.indexOf(']', i)
                val openParen = if (close > 0) s.indexOf('(', close) else -1
                val closeParen = if (openParen > 0) s.indexOf(')', openParen) else -1
                if (close > 0 && openParen == close + 1 && closeParen > 0) {
                    append(s.substring(i + 1, close))
                    i = closeParen + 1
                } else {
                    append(s[i]); i++
                }
            }

            // 裸 URL：下划线样式，不改成可点（设置页里点了也只是跳出 App，没必要）
            s.startsWith("http://", i) || s.startsWith("https://", i) -> {
                val end = s.indexOfFirst(i) { it == ' ' || it == '\n' }.let {
                    if (it < 0) s.length else it
                }
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                append(s.substring(i, end))
                pop()
                i = end
            }

            else -> {
                append(s[i]); i++
            }
        }
    }
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (idx in from until length) if (predicate(this[idx])) return idx
    return -1
}
