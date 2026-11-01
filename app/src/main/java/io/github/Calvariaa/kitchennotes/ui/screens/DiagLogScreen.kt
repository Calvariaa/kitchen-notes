package io.github.Calvariaa.kitchennotes.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.ui.DishViewModel
import io.github.Calvariaa.kitchennotes.diag.DiagLog
import io.github.Calvariaa.kitchennotes.ui.components.SectionTitle
import io.github.Calvariaa.kitchennotes.ui.components.SoftBox

/**
 * 终端日志页（设置 → 诊断 → 终端日志）。
 *
 * 存在的理由很具体：**两台手机同步出问题，但用户说不出复现步骤**。
 * logcat 拿不到、也没有后台崩溃上报，所以唯一能拿到的证据就是用户自己
 * 把这份日志复制出来发过来。为此这个页面把四件事做齐：
 *
 * 1. **看得到全部**：环境报告（版本/网段/nodeId/名册/git HEAD）+ 运行日志
 *    （git fetch/merge/push 的每一步、认证被拒的具体原因）。报告由 ViewModel
 *    快照一次，UI 不做磁盘 IO。
 * 2. **可筛选**：六万行里找「sync 对端 192.168.1.8 失败」不能靠手指滚，
 *    所以给一个纯文本过滤框 —— 不解析级别、不分类，用户想找什么就敲什么。
 * 3. **可复制 / 可存本地**：复制走剪贴板（直接粘进聊天窗口），保存写进
 *    `files/store/logs/`（Android 上没法随便往共享目录写，所以给出真实路径）。
 * 4. **可记录**：用户能自己敲一行「刚点了同步」，这样日志里被问到的那一段
 *    就有了锚点 —— 自动日志只能说明「系统做了什么」，说不清「我当时点了什么」。
 *
 * 列表用 [LazyColumn] 按行渲染，而不是一整个 Text：日志几千行时单个 Text
 * 的布局耗时会让页面卡住。等宽字体 + 横向滚动保留终端观感，不折行。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DiagLogScreen(
    vm: DishViewModel,
    onBack: () -> Unit,
) {
    val text by vm.diagText.collectAsState()
    val notes by vm.diagNotes.collectAsState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var filter by rememberSaveable { mutableStateOf("") }
    var noteDraft by rememberSaveable { mutableStateOf("") }
    // 全部展开可能有两千行，先只渲染尾部这么多；「显示更早」再放
    var visibleCount by rememberSaveable { mutableStateOf(INITIAL_LINES) }

    // 进页面就快照一次：带环境报告，用户第一次打开不用再点刷新
    LaunchedEffect(Unit) { vm.refreshDiag(includeSnapshot = true) }

    // 保存完成后给一次反馈。放在 LaunchedEffect 里按计数触发，
    // 而不是在点击回调里直接 Toast —— 写文件在 ViewModel 的协程里完成
    LaunchedEffect(vm.diagSavedTick) {
        if (vm.diagSavedTick > 0) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.diag_saved, vm.diagSavedPath),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val allLines = remember(text) { text.lines().filter { it.isNotEmpty() } }
    val filtered = remember(allLines, filter) {
        val q = filter.trim()
        if (q.isEmpty()) allLines else allLines.filter { it.contains(q, ignoreCase = true) }
    }
    // 只渲染尾部：新的日志在最后，出事前最后几行才是要看的
    val shown = remember(filtered, visibleCount) {
        if (filtered.size > visibleCount) filtered.takeLast(visibleCount) else filtered
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size, filter) {
        if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.diag_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshDiag(includeSnapshot = true) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.diag_refresh),
                        )
                    }
                    IconButton(
                        onClick = {
                            // 复制的是**完整**文本（含环境报告），不是屏幕上筛选后的部分：
                            // 排障要的是全量证据，筛选只是自己看
                            val payload = text.ifBlank { stringResource(R.string.diag_empty) }
                            clipboard.setText(AnnotatedString(payload))
                            Toast.makeText(ctx, R.string.diag_copied, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.diag_copy),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.diag_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = filter,
                onValueChange = {
                    filter = it
                    // 换过滤条件时重置展开量，否则「再看更早」的进度会对不上
                    visibleCount = INITIAL_LINES
                },
                placeholder = { Text(stringResource(R.string.diag_filter_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            // ---------- 日志正文 ----------
            SoftBox(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.diag_lines, filtered.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (filtered.size > shown.size) {
                            TextButton(
                                onClick = { visibleCount += INITIAL_LINES },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.diag_show_more, filtered.size - shown.size),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    when {
                        text.isBlank() -> EmptyHint(stringResource(R.string.diag_empty))
                        shown.isEmpty() -> EmptyHint(stringResource(R.string.diag_no_match, filter.trim()))
                        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(shown) { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = levelColor(line, MaterialTheme.colorScheme),
                                    softWrap = false,
                                    modifier = Modifier
                                        // 横向滚动放在每一行上，而不是整列：
                                        // 行与行之间的滚动位置不必一致，终端里也是各看各的
                                        .horizontalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 操作 ----------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val path = vm.saveDiagToFile()
                        if (path.isBlank()) {
                            Toast.makeText(ctx, R.string.diag_save_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.diag_save), style = MaterialTheme.typography.labelLarge) }

                OutlinedButton(
                    onClick = {
                        vm.clearDiag()
                        Toast.makeText(ctx, R.string.diag_cleared, Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.diag_clear), style = MaterialTheme.typography.labelLarge) }
            }

            // ---------- 用户输入 ----------
            SectionTitle(stringResource(R.string.diag_note_title))
            Text(
                stringResource(R.string.diag_note_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        vm.addDiagNote(noteDraft)
                        noteDraft = ""
                        Toast.makeText(ctx, R.string.diag_note_added, Toast.LENGTH_SHORT).show()
                    },
                    enabled = noteDraft.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.diag_note_add)) }
            }

            // 已记录的内容：最多显示一小块，它是给用户确认「我写了什么」的，
            // 不需要能滚很久 —— 完整内容已经在日志正文里
            if (notes.isEmpty()) {
                Text(
                    stringResource(R.string.diag_note_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SoftBox(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        notes.takeLast(6).forEach { n ->
                            Text(
                                n,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(onClick = { vm.clearDiagNotes() }) {
                            Text(
                                stringResource(R.string.diag_note_clear),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 按级别上色。不收错误类型枚举，只看那一列字符 ——
 * 日志格式就是 `HH:mm:ss.SSS L TAG msg`，在这里做结构化解析不值当。
 */
/**
 * 按级别上色。级别在 [DiagLog] 的行格式里固定是第 13 个字符：
 * `HH:mm:ss.SSS L TAG msg`。环境报告的标题行没有级别，用一套弱化的颜色。
 */
private fun levelColor(line: String, scheme: ColorScheme): Color {
    return when (line.getOrNull(13)) {
        'E' -> scheme.error
        'W' -> scheme.tertiary
        'U' -> scheme.primary
        'I' -> scheme.onSurfaceVariant
        else -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
    }
}

/** 首屏渲染行数。再多就按需加载，避免一进页面就布局上千行 */
private const val INITIAL_LINES = 400
