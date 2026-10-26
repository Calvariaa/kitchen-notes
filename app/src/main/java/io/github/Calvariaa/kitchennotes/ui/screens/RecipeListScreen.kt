package io.github.Calvariaa.kitchennotes.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.data.FamilyGroup
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.ui.i18n.text
import io.github.Calvariaa.kitchennotes.update.UpdateInfo
import io.github.Calvariaa.kitchennotes.ui.DishViewModel
import io.github.Calvariaa.kitchennotes.ui.SortDialog
import io.github.Calvariaa.kitchennotes.ui.components.DrawerMenuButton
import io.github.Calvariaa.kitchennotes.ui.components.EmptyRecipes
import io.github.Calvariaa.kitchennotes.ui.components.RecipeCard

/**
 * 列表页。
 *
 * 版式决策：
 *  - 搜索框常驻在顶栏下方而不是收进一个图标：菜谱多了以后搜索是最高频操作，
 *    藏起来要多点一次。
 *  - 标签用横向可滚动的 FilterChip，单选（再点一次取消）。不做多选是因为
 *    「家常菜 + 下饭菜」这种交集查询在这个数据量下收益不大，反而增加心智负担。
 *  - 没有任何菜谱时不显示标签行，避免空控件占位。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RecipeListScreen(
    vm: DishViewModel,
    onOpenRecipe: (String) -> Unit,
    onCreate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val updatePromptShown by vm.updatePromptShown.collectAsStateWithLifecycle()
    val lastCooked by vm.lastCooked.collectAsStateWithLifecycle()

    // 必须把 recipes / lastCooked 显式传进去，不能让 ViewModel 内部读 _recipes.value：
    // StateFlow 的 .value 不是 Compose 快照状态，读它不建立订阅，新建菜谱后
    // 列表不会重组（要切走再回来才刷新）。这里的 recipes 来自 collect，才是被观察的。
    val visible = vm.visibleRecipesOf(recipes, lastCooked)
    val tags = vm.tagsOf(recipes)

    var sortDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val g = group
                        if (g != null) {
                            Text(
                                text = when (g.role) {
                                    FamilyGroup.Role.HOST ->
                                        stringResource(R.string.list_role_host, g.name)
                                    FamilyGroup.Role.MEMBER ->
                                        stringResource(R.string.list_role_member, g.name)
                                    // NONE 时 group 已经存在但没角色，属过渡态，不显示副标题
                                    FamilyGroup.Role.NONE -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                navigationIcon = { DrawerMenuButton() },
                actions = {
                    IconButton(onClick = { sortDialog = true }) {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = stringResource(R.string.sort_label),
                        )
                    }
                    IconButton(onClick = { vm.runSync() }, enabled = !syncing) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.CloudSync,
                                contentDescription = stringResource(R.string.list_sync),
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.list_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.list_new_recipe)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding(),
        ) {
            // ---- 搜索 ----
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.list_search_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(vm.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.list_search_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            // ---- 同步提示 ----
            AnimatedVisibility(visible = syncMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = syncMessage?.text().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.clearSyncMessage() }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- 新版本横幅 ----
            // 只在真有新版时出现，且用户能一键关掉：启动时检查不等于每次启动都打扰
            val newVersion = update
            if (newVersion != null && updatePromptShown) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.update_available, newVersion.versionName),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.update_dialog_now))
                        }
                        IconButton(
                            onClick = { vm.dismissUpdatePrompt() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // ---- 标签 ----
            if (tags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tags) { tag ->
                        FilterChip(
                            selected = vm.activeTag == tag,
                            onClick = { vm.setTag(tag) },
                            label = { Text(tag) },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }
            }

            if (syncing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ---- 列表 ----
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                visible.isEmpty() -> EmptyRecipes()
                else -> LazyColumn(
                    // 底部留够 FAB 的空间
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { recipe ->
                        RecipeCard(
                            recipe = recipe,
                            imageFile = Paths.repo(io.github.Calvariaa.kitchennotes.KitchenNotesApp.instance)
                                .let { repoRoot ->
                                    if (recipe.coverImage.isBlank()) null
                                    else java.io.File(repoRoot, recipe.coverImage)
                                        .takeIf { it.exists() }
                                },
                            onClick = { onOpenRecipe(recipe.id) },
                        )
                    }
                }
            }
        }
    }

    if (sortDialog) {
        SortDialog(
            current = vm.sort,
            onPick = { vm.changeSort(it) },
            onDismiss = { sortDialog = false },
        )
    }
}
