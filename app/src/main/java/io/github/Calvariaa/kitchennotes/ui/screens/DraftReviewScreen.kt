package io.github.Calvariaa.kitchennotes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.data.Ingredient
import io.github.Calvariaa.kitchennotes.data.Recipe
import io.github.Calvariaa.kitchennotes.data.Step
import io.github.Calvariaa.kitchennotes.ui.DishViewModel
import io.github.Calvariaa.kitchennotes.ui.components.DifficultyDots
import io.github.Calvariaa.kitchennotes.ui.components.SectionTitle
import io.github.Calvariaa.kitchennotes.ui.components.SoftBox
import io.github.Calvariaa.kitchennotes.ui.i18n.text

/**
 * 生成结果预览页。
 *
 * 这一页是「AI 草稿 → 正式菜谱」的闸门：AI 输出不可能百分百对，
 * 用户在保存前能看到完整材料与步骤，可以就地删改，
 * 也可以一句话让 AI 重写（「太辣了，改微辣」「步骤合并成 5 步」）。
 * 直接入库再改的路径体验更差，因为改完又要同步一次。
 *
 * i18n：本页所有文案走资源；编辑中的字段值（菜名、材料名、步骤正文）是**内容**，
 * 不翻译 —— 用户写什么就存什么。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DraftReviewScreen(
    vm: DishViewModel,
    recipe: Recipe,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val generating by vm.generating.collectAsState()
    val stage by vm.generateStage.collectAsState()
    val error by vm.generateError.collectAsState()

    // 可编辑的本地副本
    var title by remember(recipe.id) { mutableStateOf(recipe.title) }
    var summary by remember(recipe.id) { mutableStateOf(recipe.summary) }
    var serving by remember(recipe.id) { mutableStateOf(recipe.serving) }
    var minutes by remember(recipe.id) { mutableStateOf(recipe.totalMinutes?.toString() ?: "") }
    val ingredients = remember(recipe.id) {
        mutableStateListOf<Ingredient>().apply { addAll(recipe.ingredients) }
    }
    val steps = remember(recipe.id) {
        mutableStateListOf<Step>().apply { addAll(recipe.steps) }
    }
    var refineText by remember { mutableStateOf("") }

    val untitled = stringResource(R.string.untitled_dish)

    fun current(): Recipe = recipe.copy(
        title = title.ifBlank { untitled },
        summary = summary,
        serving = serving,
        totalMinutes = minutes.toIntOrNull(),
        ingredients = ingredients.toList(),
        steps = steps.toList(),
        updatedAt = System.currentTimeMillis(),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.draft_title),
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.draft_discard)) }
                    Button(
                        onClick = {
                            val finalRecipe = current()
                            vm.commitDraft(finalRecipe)
                            onSaved(finalRecipe.id)
                        },
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.draft_save))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (generating) {
                Column {
                    Text(
                        stage?.text() ?: stringResource(R.string.draft_ai_rewriting),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        error?.text().orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // ---- 基本信息 ----
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.draft_label_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it },
                        label = { Text(stringResource(R.string.draft_label_summary)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = serving,
                            onValueChange = { serving = it },
                            label = { Text(stringResource(R.string.draft_label_serving)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text(stringResource(R.string.draft_label_minutes)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.draft_label_difficulty),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.width(10.dp))
                        DifficultyDots(recipe.difficulty)
                    }
                }
            }

            // ---- 材料 ----
            SectionTitle(stringResource(R.string.draft_ingredients_title))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ingredients.forEachIndexed { index, ing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = ing.name,
                                onValueChange = { v ->
                                    ingredients[index] = ing.copy(name = v)
                                },
                                label = {
                                    Text(
                                        stringResource(R.string.draft_label_ingredient),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(
                                value = ing.amount,
                                onValueChange = { v ->
                                    ingredients[index] = ing.copy(amount = v)
                                },
                                label = {
                                    Text(
                                        stringResource(R.string.draft_label_amount),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1.4f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            IconButton(onClick = { ingredients.removeAt(index) }) {
                                Text("×", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    TextButton(
                        onClick = { ingredients.add(Ingredient(name = "", amount = "")) },
                    ) { Text(stringResource(R.string.draft_add_ingredient)) }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // ---- 步骤 ----
            SectionTitle(stringResource(R.string.draft_steps_title))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEachIndexed { index, step ->
                    SoftBox(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.draft_step_header, step.index),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.weight(1f))
                                step.durationMinutes?.let {
                                    Text(
                                        stringResource(R.string.draft_about_minutes, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { steps.removeAt(index) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Text("×", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            if (step.title.isNotBlank()) {
                                Text(
                                    step.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                step.detail,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // ---- 让 AI 重写 ----
            SectionTitle(stringResource(R.string.draft_refine_title))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = refineText,
                        onValueChange = { refineText = it },
                        placeholder = { Text(stringResource(R.string.draft_refine_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = {
                            vm.refineDraft(refineText)
                            refineText = ""
                        },
                        enabled = refineText.isNotBlank() && !generating,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.draft_refine_action))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
