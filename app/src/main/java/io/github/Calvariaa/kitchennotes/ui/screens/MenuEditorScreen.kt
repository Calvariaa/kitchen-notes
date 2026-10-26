package io.github.Calvariaa.kitchennotes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.data.MealSlot
import io.github.Calvariaa.kitchennotes.data.MenuItem
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.Recipe
import io.github.Calvariaa.kitchennotes.data.Staple
import io.github.Calvariaa.kitchennotes.ui.DishViewModel
import io.github.Calvariaa.kitchennotes.ui.components.SectionTitle
import io.github.Calvariaa.kitchennotes.ui.components.SoftBox
import io.github.Calvariaa.kitchennotes.ui.formatTimestamp
import io.github.Calvariaa.kitchennotes.ui.mealSlotLabel
import io.github.Calvariaa.kitchennotes.ui.menuTitle
import java.io.File
import java.time.LocalDate

/**
 * 菜单编辑页。
 *
 * 版式顺序 = 做这顿饭的决策顺序：先定日期与餐次（决定标题），再定主食，
 * 再勾菜，最后看汇总出来的材料。材料是只读的派生视图，不能直接编辑 ——
 * 它来自菜谱，改了菜谱它会跟着变，两处可编辑必然对不上。
 *
 * 所有改动即时落盘（[DishViewModel.saveMenu]），没有「保存」按钮：
 * 手机上没有理由让用户担心「我勾的菜会不会丢」。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MenuEditorScreen(
    vm: DishViewModel,
    menuId: String,
    onBack: () -> Unit,
) {
    val menus by vm.menus.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val menu = menus.firstOrNull { it.id == menuId }

    var pickDishes by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (menu == null) {
        // 删掉之后 _menus 会先于导航更新，这里不该画「加载中」——
        // 一个已经消失的菜单不该出现加载态。直接画空白等返回撤走这一帧。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!deleting) CircularProgressIndicator()
        }
        return
    }

    // recipes 显式传入，不让 ViewModel 内部读 _recipes.value — 见 RecipeListScreen 的注释
    val ingredients = vm.menuIngredients(menu, recipes)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = menuTitle(menu),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete),
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
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ---------------- 日期与餐次 ----------------
            SectionTitle(stringResource(R.string.menu_section_date))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealSlot.entries.forEach { slot ->
                    FilterChip(
                        selected = menu.slot == slot,
                        onClick = { vm.setMenuSlot(menu.id, slot) },
                        label = { Text(mealSlotLabel(slot)) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            DateField(
                date = menu.date,
                onDateChange = { vm.setMenuDate(menu.id, it) },
            )

            Spacer(Modifier.height(10.dp))

            // ---------------- 主食 ----------------
            SectionTitle(stringResource(R.string.menu_section_staples))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Staple.entries.forEach { staple ->
                    FilterChip(
                        selected = menu.staples.contains(staple),
                        onClick = { vm.toggleMenuStaple(menu.id, staple) },
                        label = { Text(stringResource(staple.labelRes)) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---------------- 菜品 ----------------
            SectionTitle(
                stringResource(R.string.menu_section_dishes),
                trailing = {
                    TextButton(onClick = { pickDishes = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.menu_pick_dishes))
                    }
                },
            )

            if (menu.items.isEmpty()) {
                SoftBox(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.menu_no_dishes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    menu.items.forEach { item ->
                        MenuItemCard(
                            item = item,
                            // 菜谱可能已被删除（菜单里只留了标题快照），
                            // 这时打分仍然有意义 —— 记的是「当时吃着怎么样」
                            onRate = { stars -> vm.rateMenuItem(menu.id, item.recipeId, stars) },
                            onRemove = { vm.toggleMenuRecipe(menu.id, item.recipeId) },
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.menu_rate_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(14.dp))

            // ---------------- 材料汇总 ----------------
            SectionTitle(
                stringResource(R.string.menu_section_ingredients),
                trailing = {
                    Text(
                        stringResource(R.string.menu_ingredients_count, ingredients.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (ingredients.isEmpty()) {
                        Text(
                            stringResource(R.string.menu_ingredients_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ingredients.forEach { ing ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = ing.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (ing.amounts.isNotEmpty()) {
                                    Text(
                                        // 多个用量并列而不是相加：单位不统一，算错比不算更糟
                                        text = ing.amounts.joinToString(" + "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---------------- 备注 ----------------
            SectionTitle(stringResource(R.string.menu_section_note))
            OutlinedTextField(
                value = menu.note,
                onValueChange = { vm.setMenuNote(menu.id, it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.menu_note_placeholder)) },
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
            )
        }
    }

    if (pickDishes) {
        DishPickerDialog(
            recipes = recipes,
            selected = menu.items.map { it.recipeId }.toSet(),
            onToggle = { recipeId -> vm.toggleMenuRecipe(menu.id, recipeId) },
            onDismiss = { pickDishes = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.menu_delete)) },
            text = { Text(stringResource(R.string.menu_delete_body, menuTitle(menu))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        deleting = true
                        vm.deleteMenu(menu)
                        onBack()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 日期输入。
 *
 * 用文本框而不是 DatePickerDialog：Material3 的日期选择器要引入
 * `material3` 的 experimental 日期组件，还会在中文环境下和「今天/昨天」心智
 * 打架。这里用户通常只是改「哪天吃的」，直接给可编辑的 `yyyy-MM-dd`，
 * 旁边放两个快捷按钮更省事。
 */
@Composable
private fun DateField(date: String, onDateChange: (String) -> Unit) {
    var text by remember(date) { mutableStateOf(date) }
    val valid = runCatching { LocalDate.parse(text) }.isSuccess

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // 只在格式合法时落盘，避免中间态（"2026-1"）把数据写坏
                if (runCatching { LocalDate.parse(it) }.isSuccess) onDateChange(it)
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
        )
        TextButton(onClick = {
            val today = LocalDate.now().toString()
            text = today
            onDateChange(today)
        }) { Text(stringResource(R.string.menu_date_today)) }
    }
}

/** 菜单里的一道菜：名字 + 星标 + 移除 */
@Composable
private fun MenuItemCard(
    item: MenuItem,
    onRate: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 5 颗星，再点一次当前分数就是取消（0 分 = 没打分）
                (1..5).forEach { star ->
                    val filled = star <= item.rating
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                onRate(if (item.rating == star) 0 else star)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (filled) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                val ratedAt = item.ratedAt
                if (ratedAt != null) {
                    Text(
                        text = stringResource(R.string.menu_rated_at, formatTimestamp(ratedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    // 明显比「再点一次同一颗星」好发现：取消评分是个真实需求
                    TextButton(
                        onClick = { onRate(0) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            stringResource(R.string.menu_clear_rating),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 选菜弹窗：缩略图网格 + 顶部搜索，边勾边生效，点完成关闭。
 *
 * 为什么不是 AlertDialog 加一列 Checkbox：菜谱多了以后一屏只放得下七八行，
 * 而人对「哪道菜」的识别主要靠图不是靠字。改成小卡片网格后一屏能看到 12–15 道，
 * 而且缩略图直接就是列表页那张封面，两处视觉记忆能对上。
 *
 * 用 Dialog 而不是 AlertDialog：AlertDialog 的正文宽度被平台默认宽度压住
 * （手机竖屏约 280dp），三列网格每格只剩 80dp 出头，图会小到没意义。
 * usePlatformDefaultWidth = false 才能拿到接近全屏的宽度。
 *
 * 勾选是即时提交（每次点击回调给 ViewModel），所以这里没有「取消」——
 * 用户勾错了直接再点一下取消勾选即可，不需要撤销语义。空勾选态下按返回也能退出。
 */
@Composable
private fun DishPickerDialog(
    recipes: List<Recipe>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(recipes, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) recipes else recipes.filter { it.searchBlob().contains(q) }
    }

    // LocalContext.current 必须在组合上下文里读，不能塞进 remember 的 lambda
    val context = LocalContext.current
    val repoRoot = remember(context) { Paths.repo(context.applicationContext) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                // 上限 560dp 而不是铺满：网格在小屏上过高时，标题和搜索框会被
                // 推到视觉重心之外，而这里最该一眼看到的是菜。
                .heightIn(max = 560.dp)
                // 搜索框获得焦点后键盘会盖住下半屏，imePadding 让网格自然收缩
                .imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.menu_pick_dishes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.menu_pick_count, selected.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    placeholder = { Text(stringResource(R.string.list_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )

                Spacer(Modifier.height(12.dp))

                when {
                    recipes.isEmpty() -> PickerHint(stringResource(R.string.menu_no_recipes))
                    filtered.isEmpty() -> PickerHint(stringResource(R.string.menu_pick_no_match))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 96.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        items(filtered, key = { it.id }) { recipe ->
                            PickCard(
                                recipe = recipe,
                                coverFile = recipe.coverImage.takeIf { it.isNotBlank() }
                                    ?.let { File(repoRoot, it) }?.takeIf { it.exists() },
                                checked = selected.contains(recipe.id),
                                onClick = { onToggle(recipe.id) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
        }
    }
}

/** 一张可选的小卡片：正方形缩略图 + 一行菜名，右上角勾选角标 */
@Composable
private fun PickCard(
    recipe: Recipe,
    coverFile: File?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                // 选中态用描边而不是改底色：底色一变，图的观感也跟着变
                .then(
                    if (checked) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(14.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = recipe.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Restaurant,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(28.dp),
                )
            }

            // 角标放在图片容器内，clip 之后仍然会被圆角裁掉，所以单独加在 Box 之后不行。
            // 用 align(Alignment.TopEnd) 并留 4dp 内边距即可落在圆角内侧。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (checked) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = null,
                    tint = if (checked) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = recipe.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun PickerHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
