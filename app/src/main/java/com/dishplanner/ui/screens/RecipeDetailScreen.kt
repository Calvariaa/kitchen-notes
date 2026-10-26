package com.dishplanner.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dishplanner.data.Ingredient
import com.dishplanner.data.Paths
import com.dishplanner.data.Recipe
import com.dishplanner.data.Step
import com.dishplanner.ui.DishViewModel
import com.dishplanner.ui.components.DifficultyDots
import com.dishplanner.ui.components.SectionTitle
import com.dishplanner.ui.components.SoftBox
import java.io.File

/**
 * 菜谱详情页。
 *
 * 排版顺序照「做饭时的实际阅读顺序」排：大图 → 名字与关键信息 → 材料清单（可勾选）
 * → 步骤（大字号、带序号、带火候与耗时）→ 小贴士。
 * 材料做成可勾选的购物式清单：真正下厨的人会边备料边划掉。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RecipeDetailScreen(
    vm: DishViewModel,
    recipeId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val recipes by vm.recipes.collectAsState()
    val recipe = recipes.firstOrNull { it.id == recipeId }

    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (recipe == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = !editing }) {
                        Icon(
                            if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                            contentDescription = if (editing) "完成" else "编辑",
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
        ) {
            // ---- 大图 ----
            val cover = Paths.repo(com.dishplanner.DishPlannerApp.instance).let { root ->
                if (recipe.coverImage.isBlank()) null else File(root, recipe.coverImage)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null && cover.exists()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = recipe.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.Restaurant,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }

            // 其余图片缩略
            if (recipe.images.size > 1) {
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recipe.images.filter { it != recipe.coverImage }) { rel ->
                        val f = File(Paths.repo(com.dishplanner.DishPlannerApp.instance), rel)
                        if (f.exists()) {
                            AsyncImage(
                                model = f,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))

                // ---- 标题区 ----
                if (editing) {
                    var title by remember(recipe.id) { mutableStateOf(recipe.title) }
                    var summary by remember(recipe.id) { mutableStateOf(recipe.summary) }
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            vm.saveEdited(recipe.copy(title = it))
                        },
                        label = { Text("菜名") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = summary,
                        onValueChange = {
                            summary = it
                            vm.saveEdited(recipe.copy(summary = it))
                        },
                        label = { Text("简介") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Text(
                        text = recipe.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (recipe.summary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = recipe.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- 关键信息行 ----
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    recipe.totalMinutes?.let { InfoPill("$it 分钟", Icons.Filled.Timer) }
                    InfoPill(recipe.serving.ifBlank { "份量自定" }, null)
                    Spacer(Modifier.weight(1f))
                    DifficultyDots(recipe.difficulty)
                }

                if (recipe.tags.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(recipe.tags) { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ---- 材料 ----
                SectionTitle("材料", trailing = {
                    Text(
                        "${recipe.ingredients.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                })
                Spacer(Modifier.height(8.dp))

                val checked = remember(recipe.id) { mutableSetOf<Int>() }
                SoftBox(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        recipe.ingredients.forEachIndexed { index, ing ->
                            IngredientRow(
                                ingredient = ing,
                                checked = checked.contains(index),
                                onToggle = {
                                    if (!checked.add(index)) checked.remove(index)
                                    // 触发重组
                                    vm.setQuery(vm.searchQuery)
                                },
                            )
                        }
                        if (recipe.ingredients.isEmpty()) {
                            Text(
                                "没有材料信息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ---- 步骤 ----
                SectionTitle("做法")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    recipe.steps.forEach { step -> StepBlock(step) }
                }

                // ---- 小贴士 ----
                if (recipe.tips.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("小贴士")
                    Spacer(Modifier.height(8.dp))
                    SoftBox(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recipe.tips.forEach { tip ->
                                Row {
                                    Text(
                                        "· ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        tip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- 元信息 ----
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = buildString {
                        if (recipe.author.isNotBlank()) append("由 ${recipe.author} 整理")
                        if (recipe.sourcePlatform.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("来源 ${recipe.sourcePlatform}")
                        }
                        if (recipe.authorEmail.isNotBlank()) append("\n${recipe.authorEmail}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun InfoPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun IngredientRow(
    ingredient: Ingredient,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = ingredient.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (ingredient.amount.isNotBlank()) {
                Text(
                    text = ingredient.amount,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 步骤块：大序号 + 标题 + 详细内容 + 耗时/火候 */
@Composable
private fun StepBlock(step: Step) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${step.index}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (step.title.isNotBlank()) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                text = step.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.15f,
            )
            if (step.durationMinutes != null || step.heat != null) {
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    step.heat?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    step.durationMinutes?.let {
                        Text(
                            "约 $it 分钟",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
