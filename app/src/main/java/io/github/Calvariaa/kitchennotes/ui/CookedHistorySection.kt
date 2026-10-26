package io.github.Calvariaa.kitchennotes.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.data.CookedRecord
import io.github.Calvariaa.kitchennotes.ui.components.SectionTitle
import io.github.Calvariaa.kitchennotes.ui.components.SoftBox

/**
 * 菜谱详情页最底部的「做过的记录」，默认折叠。
 *
 * 折叠的理由：大多数时候用户是来查做法的，历史对这一次下厨没有信息量；
 * 但它必须存在且容易找到 —— 「上次做是什么时候、当时给了几分」是决定
 * 「今晚还做这道吗」的关键信息。所以放在底部、默认收起、标题上直接给次数。
 */
@Composable
fun CookedHistorySection(
    vm: DishViewModel,
    recipeId: String,
    onOpenMenu: (String) -> Unit,
) {
    val history by vm.cookedHistory.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    // 进详情页就加载，标题上的「做过 N 次」要立刻正确，
    // 不能等用户展开才去查 —— 那样折叠状态的数字会是 0。
    androidx.compose.runtime.LaunchedEffect(recipeId) {
        vm.loadCookedHistory(recipeId)
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Spacer(Modifier.height(10.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(
            text = stringResource(R.string.detail_history_title),
            modifier = Modifier.weight(1f),
            trailing = {
                if (history.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.detail_history_count, history.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
            },
        )
        val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .rotate(angle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (expanded) {
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.detail_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                history.forEach { record ->
                    HistoryRow(record = record, onClick = { onOpenMenu(record.menuId) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: CookedRecord, onClick: () -> Unit) {
    SoftBox(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.menu_title_format,
                        formatFullDate(record.date),
                        mealSlotLabel(record.slot),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val ratedAt = record.ratedAt
                if (ratedAt != null) {
                    Text(
                        text = stringResource(R.string.menu_rated_at, formatTimestamp(ratedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            // 没打分就不画星星，也不用灰星凑数 —— 空着比 5 个灰点更清楚
            if (record.rating > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(record.rating) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

/** 排序选择弹窗，列表页顶栏调用 */
@Composable
fun SortDialog(
    current: io.github.Calvariaa.kitchennotes.data.RecipeSort,
    onPick: (io.github.Calvariaa.kitchennotes.data.RecipeSort) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_dialog_title)) },
        text = {
            Column {
                io.github.Calvariaa.kitchennotes.data.RecipeSort.entries.forEach { option ->
                    val selected = option == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            )
                            .clickable {
                                onPick(option)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
