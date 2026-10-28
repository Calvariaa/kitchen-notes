package io.github.Calvariaa.kitchennotes.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.sync.ConflictEntry
import io.github.Calvariaa.kitchennotes.sync.ConflictReport

/**
 * 同步冲突的结果弹窗。
 *
 * 设计取舍：**不在同步过程中询问**「合并还是覆盖」，而是事后告知结果。
 * 理由是合并发生在后台线程（`postReceive` 的 daemon 线程、定时同步协程），
 * 让它们停下来等用户点按钮会堵死整条同步链；而且全互联 P2P 下没有「主机」，
 * 「谁先合并」取决于谁先点了同步，询问框会落到随机一台设备上。
 * 真正的数据安全靠两层保证：字段级三方合并（不同字段两边都保住）+
 * 落败版本落盘备份到 `files/store/conflicts/`。
 *
 * 所以这个弹窗只做一件事：让用户知道**发生过什么、哪一边被保留了**。
 */
@Composable
fun ConflictDialog(report: ConflictReport?, onDismiss: () -> Unit) {
    if (report == null || report.isEmpty) return

    val lost = report.entries.filter { it.lostSomething }
    val merged = report.entries.filter { !it.lostSomething }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (report.unresolved > 0) {
                    Text(
                        text = stringResource(R.string.conflict_intro_unresolved, report.unresolved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (lost.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.conflict_intro_lost),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    lost.forEach { entry ->
                        EntryBlock(entry, lostSomething = true)
                    }
                }
                if (merged.isNotEmpty()) {
                    if (lost.isNotEmpty()) Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.conflict_intro_merged),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    merged.forEach { entry ->
                        EntryBlock(entry, lostSomething = false)
                    }
                }
                if (lost.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.conflict_backup_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_ok)) }
        },
    )
}

/**
 * 一条记录的明细。
 *
 * 字段名直接显示英文 key（`summary` / `tips`）：那是 JSON 里的真实键名，
 * **翻译它会和用户打开文件看到的内容对不上**。记录本身用 id 标识，
 * 因为这里拿不到仓库层，查名字得额外读一次盘。
 */
@Composable
private fun EntryBlock(entry: ConflictEntry, lostSomething: Boolean) {
    val kind = stringResource(
        if (entry.kind == ConflictEntry.Kind.DISH) R.string.conflict_kind_dish
        else R.string.conflict_kind_menu,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .heightIn(min = 20.dp),
    ) {
        Text(
            text = "$kind · ${entry.id}",
            style = MaterialTheme.typography.labelMedium,
        )
        (if (lostSomething) entry.fields else entry.autoMerged).forEach { field ->
            Row(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = "· ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (lostSomething) R.string.conflict_item_lost
                        else R.string.conflict_item_merged,
                        field,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
