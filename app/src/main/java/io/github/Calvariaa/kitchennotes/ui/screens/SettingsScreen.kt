package io.github.Calvariaa.kitchennotes.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.ai.DeepSeekClient
import io.github.Calvariaa.kitchennotes.data.FamilyGroup
import io.github.Calvariaa.kitchennotes.ui.DishViewModel
import io.github.Calvariaa.kitchennotes.ui.components.SectionTitle
import io.github.Calvariaa.kitchennotes.ui.components.SoftBox
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.Calvariaa.kitchennotes.BuildConfig
import io.github.Calvariaa.kitchennotes.ui.i18n.AppLanguage
import io.github.Calvariaa.kitchennotes.ui.i18n.text
import io.github.Calvariaa.kitchennotes.update.UpdateInfo
import io.github.Calvariaa.kitchennotes.ui.theme.Palette

/**
 * 设置页。分六块：AI 服务、主题、语言、家庭组同步、身份、数据。
 *
 * i18n 注意：
 *  - 所有静态文案走 `stringResource`，带数字的走格式化资源（`%1$d photos` 与 `%1$d 张`）。
 *  - 配色名与说明在 [Palette] 里存的是**资源 id**（labelRes / descriptionRes），
 *    枚举不存字面量，所以它们的语言也跟着应用内语言切换。
 *  - 测试连接的结果是 ViewModel 产出的 [io.github.Calvariaa.kitchennotes.ui.i18n.UiText]，
 *    用 `.text()` 解析，切换语言后自动跟着变。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    vm: DishViewModel,
    onBack: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val identity by vm.identity.collectAsState()
    val group by vm.group.collectAsState()
    val aiResult by vm.aiTestResult.collectAsState()
    val hostRunning by vm.hostRunning.collectAsState()
    val hostAddresses by vm.hostAddresses.collectAsState()
    val syncMessage by vm.syncMessage.collectAsState()
    val aiTestOk by vm.aiTestOk.collectAsState()
    val update by vm.update.collectAsState()
    val updateMessage by vm.updateMessage.collectAsState()
    val updateChecking by vm.updateChecking.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var apiKeyVisible by remember { mutableStateOf(false) }
    var showJoinChoice by remember { mutableStateOf(false) }
    var showHostNameDialog by remember { mutableStateOf(false) }

    // 用户可能被我们送去「安装未知应用」设置页，回来时要把没走完的安装接上。
    // 用 Activity 的 RESUME 生命周期做触发点：比轮询权限状态省电，也比要求用户
    // 再点一次按钮自然。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.resumePendingInstall()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ==================== AI ====================
            SectionTitle(stringResource(R.string.settings_section_ai))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = settings.apiBaseUrl,
                        onValueChange = { v -> vm.updateSettings { it.copy(apiBaseUrl = v) } },
                        label = { Text(stringResource(R.string.settings_api_base_url)) },
                        placeholder = { Text("https://api.deepseek.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeepSeekClient.BASE_URL_PRESETS.forEach { url ->
                            AssistChip(
                                onClick = { vm.updateSettings { it.copy(apiBaseUrl = url) } },
                                label = {
                                    Text(
                                        url.removePrefix("https://"),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { v -> vm.updateSettings { it.copy(apiKey = v.trim()) } },
                        label = { Text(stringResource(R.string.settings_api_key)) },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Text(
                                    stringResource(
                                        if (apiKeyVisible) R.string.action_hide else R.string.action_show,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )

                    OutlinedTextField(
                        value = settings.model,
                        onValueChange = { v -> vm.updateSettings { it.copy(model = v.trim()) } },
                        label = { Text(stringResource(R.string.settings_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeepSeekClient.MODEL_PRESETS.forEach { m ->
                            FilterChip(
                                selected = settings.model == m,
                                onClick = { vm.updateSettings { it.copy(model = m) } },
                                label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SettingSwitch(
                        title = stringResource(R.string.settings_send_images),
                        subtitle = stringResource(R.string.settings_send_images_sub),
                        checked = settings.sendImagesToModel,
                        onChange = { v -> vm.updateSettings { it.copy(sendImagesToModel = v) } },
                    )
                    SettingSwitch(
                        title = stringResource(R.string.settings_thinking),
                        subtitle = stringResource(R.string.settings_thinking_sub),
                        checked = settings.thinkingEnabled,
                        onChange = { v -> vm.updateSettings { it.copy(thinkingEnabled = v) } },
                    )

                    SliderRow(
                        label = stringResource(R.string.settings_max_images),
                        value = settings.maxImagesPerRequest.toFloat(),
                        range = 1f..9f,
                        steps = 7,
                        display = stringResource(
                            R.string.settings_max_images_value,
                            settings.maxImagesPerRequest,
                        ),
                        onChange = { v ->
                            vm.updateSettings { it.copy(maxImagesPerRequest = v.toInt()) }
                        },
                    )
                    SliderRow(
                        label = stringResource(R.string.settings_image_edge),
                        value = settings.imageMaxEdge.toFloat(),
                        range = 800f..2400f,
                        steps = 7,
                        display = stringResource(
                            R.string.settings_image_edge_value,
                            settings.imageMaxEdge,
                        ),
                        onChange = { v ->
                            // 吸附到 200 的整数倍，避免存储里出现 1837 这种怪值
                            val snapped = (v / 200).toInt() * 200
                            vm.updateSettings { it.copy(imageMaxEdge = snapped.coerceIn(800, 2400)) }
                        },
                    )

                    OutlinedTextField(
                        value = settings.promptAddon,
                        onValueChange = { v -> vm.updateSettings { it.copy(promptAddon = v) } },
                        label = { Text(stringResource(R.string.settings_prompt_addon)) },
                        placeholder = { Text(stringResource(R.string.settings_prompt_addon_placeholder)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                        shape = RoundedCornerShape(14.dp),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.testAi() },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(stringResource(R.string.settings_test_connection)) }
                    }

                    AnimatedVisibility(aiResult != null) {
                        // 成功/失败用不同的颜色，判断依据是 ViewModel 给的 ok 标志，
                        // 不靠比对文案前缀（那玩意一翻译就失效）
                        Text(
                            text = aiResult?.text().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (aiTestOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ==================== 主题 ====================
            SectionTitle(stringResource(R.string.settings_section_appearance))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.settings_palette),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 8 套配色，两列网格
                    Palette.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { palette ->
                                PaletteSwatch(
                                    palette = palette,
                                    selected = settings.paletteId == palette.id,
                                    enabled = !settings.dynamicColor,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        vm.updateSettings { it.copy(paletteId = palette.id) }
                                    },
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    SettingSwitch(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_sub),
                        checked = settings.dynamicColor,
                        onChange = { v -> vm.updateSettings { it.copy(dynamicColor = v) } },
                    )

                    Text(
                        stringResource(R.string.settings_theme_mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "system" to R.string.settings_theme_system,
                            "light" to R.string.settings_theme_light,
                            "dark" to R.string.settings_theme_dark,
                        ).forEach { (id, labelRes) ->
                            FilterChip(
                                selected = settings.themeMode == id,
                                onClick = { vm.updateSettings { it.copy(themeMode = id) } },
                                label = { Text(stringResource(labelRes)) },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                }
            }

            // ==================== 语言 ====================
            SectionTitle(stringResource(R.string.settings_language))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguage.selectable.forEach { tag ->
                        FilterChip(
                            selected = settings.language == tag,
                            onClick = { vm.updateSettings { it.copy(language = tag) } },
                            label = { Text(AppLanguage.displayName(tag, ctx)) },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
            }

            // ==================== 家庭组 ====================
            SectionTitle(stringResource(R.string.settings_section_sync))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_sync_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val g = group
                    when (g?.role) {
                        FamilyGroup.Role.HOST -> {
                            Text(
                                stringResource(
                                    if (hostRunning) R.string.settings_host_running
                                    else R.string.settings_host_stopped,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (hostAddresses.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.settings_host_addresses),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                hostAddresses.forEach { addr ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            addr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { clipboard.setText(AnnotatedString(addr)) },
                                        ) {
                                            Icon(
                                                Icons.Filled.ContentCopy,
                                                contentDescription = stringResource(R.string.action_copy),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.settings_join_code, g.token),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { identity?.let { vm.startHost(it, g.name) } },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text(stringResource(R.string.settings_restart_host)) }
                                TextButton(onClick = { vm.stopHost() }) {
                                    Text(stringResource(R.string.settings_dissolve_group))
                                }
                            }
                        }

                        FamilyGroup.Role.MEMBER -> {
                            Text(
                                stringResource(R.string.settings_joined_group, g.name),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.settings_host_address, g.hostAddress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { vm.runSync() },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text(stringResource(R.string.settings_sync_now)) }
                                TextButton(onClick = { vm.leaveGroup() }) {
                                    Text(stringResource(R.string.settings_leave_group))
                                }
                            }
                        }

                        else -> {
                            Text(
                                stringResource(R.string.settings_no_group),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { showHostNameDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text(stringResource(R.string.settings_become_host)) }
                                OutlinedButton(
                                    onClick = { showJoinChoice = true },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text(stringResource(R.string.settings_join_group)) }
                            }
                        }
                    }

                    AnimatedVisibility(syncMessage != null) {
                        Text(
                            syncMessage?.text().orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    SettingSwitch(
                        title = stringResource(R.string.settings_sync_on_launch),
                        subtitle = null,
                        checked = settings.syncOnLaunch,
                        onChange = { v -> vm.updateSettings { it.copy(syncOnLaunch = v) } },
                    )
                    SettingSwitch(
                        title = stringResource(R.string.settings_push_after_edit),
                        subtitle = null,
                        checked = settings.pushAfterEdit,
                        onChange = { v -> vm.updateSettings { it.copy(pushAfterEdit = v) } },
                    )
                }
            }

            // ==================== 身份 ====================
            SectionTitle(stringResource(R.string.settings_section_identity))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        identity?.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        identity?.email.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val pub = identity?.sshPublicKey.orEmpty()
                    if (pub.isNotBlank()) {
                        Text(
                            stringResource(R.string.settings_ssh_fingerprint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            pub.take(24) + "…" + pub.takeLast(20),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(pub)) }) {
                                Text(stringResource(R.string.settings_copy_public_key))
                            }
                            TextButton(onClick = { vm.regenerateSshKey() }) {
                                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.settings_regenerate_key))
                            }
                        }
                    }
                }
            }

            // ==================== 更新 ====================
            SectionTitle(stringResource(R.string.settings_section_update))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val info = update
                    if (info != null) {
                        // 有新版本：把 tag、发布日期和更新内容直接摊开，
                        // 用户不必去浏览器翻 Release 页才知道改了什么
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.update_available, info.versionName),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (info.publishedAt.isNotBlank()) {
                                Text(
                                    stringResource(
                                        R.string.update_release_date,
                                        info.publishedAt.take(10),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (info.notes.isNotBlank()) {
                                Text(
                                    stringResource(R.string.update_section_notes),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    info.notes.trim().take(1200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (updateProgress >= 0) {
                        // 下载中：进度条 + 百分比。家庭 WiFi 拉 20MB 十几秒，
                        // 没有进度用户会以为卡死
                        LinearProgressIndicator(
                            progress = { updateProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.checkUpdate() },
                            enabled = !updateChecking && updateProgress < 0,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.settings_check_update))
                        }
                        if (info != null) {
                            OutlinedButton(
                                onClick = { vm.downloadAndInstall() },
                                enabled = updateProgress < 0,
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(stringResource(R.string.update_download)) }
                        }
                    }

                    AnimatedVisibility(updateMessage != null) {
                        Text(
                            updateMessage?.text().orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )

                    // 仓库地址可改：有人可能 fork 到自己账号下，CI 会把包发到那儿
                    OutlinedTextField(
                        value = settings.updateRepo,
                        onValueChange = { v ->
                            vm.updateSettings { it.copy(updateRepo = v.trim()) }
                        },
                        label = { Text(stringResource(R.string.settings_update_repo)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    SettingSwitch(
                        title = stringResource(R.string.settings_check_update_on_launch),
                        subtitle = null,
                        checked = settings.checkUpdateOnLaunch,
                        onChange = { v -> vm.updateSettings { it.copy(checkUpdateOnLaunch = v) } },
                    )
                    SettingSwitch(
                        title = stringResource(R.string.settings_include_prerelease),
                        subtitle = null,
                        checked = settings.updateIncludePrerelease,
                        onChange = { v -> vm.updateSettings { it.copy(updateIncludePrerelease = v) } },
                    )
                }
            }

            // ==================== 数据 ====================
            SectionTitle(stringResource(R.string.settings_section_data))
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        vm.storageSummary().text(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        vm.repoPath(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_storage_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    JoinChoiceDialog(
        show = showJoinChoice,
        onDismiss = { showJoinChoice = false },
        onHost = {
            showJoinChoice = false
            showHostNameDialog = true
        },
        onJoin = {
            showJoinChoice = false
            vm.joinFlow = true
        },
    )

    HostNameDialog(
        show = showHostNameDialog,
        onDismiss = { showHostNameDialog = false },
        onConfirm = { name ->
            showHostNameDialog = false
            identity?.let { vm.startHost(it, name) }
        },
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                display,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/**
 * 配色预览块。
 *
 * 故意用「固定色块」而不是把整个 Theme 切过去预览：切主题会重建 MaterialTheme，
 * 在一个列表里给 8 个选项分别套主题代价太大而且会闪。
 * 这里直接把 representative 的三个色值画出来，用户已经能判断是不是自己要的调子。
 *
 * 名称与说明按 `palette_<id>` / `palette_<id>_desc` 的约定去资源里取，
 * 枚举里只存 id —— 文案随语言切换，id 不变。
 */
@Composable
private fun PaletteSwatch(
    palette: Palette,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = palette.light
    val alpha = if (enabled) 1f else 0.35f

    Card(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = t.surfaceContainerLow.copy(alpha = if (enabled) 1f else 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(9.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(t.primary, t.secondary, t.tertiary).forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(c.copy(alpha = alpha)),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            // 文字颜色用该配色自己的 onSurface，保证在任何主题下都读得清
            Text(
                stringResource(palette.labelRes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = t.onSurface.copy(alpha = alpha),
            )
            Text(
                stringResource(palette.descriptionRes),
                style = MaterialTheme.typography.labelSmall,
                color = t.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 2,
            )
        }
    }
}

/** 加入家庭组：先问是当主机还是加入别人 */
@Composable
fun JoinChoiceDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_group_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.settings_group_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onHost) { Text(stringResource(R.string.settings_become_host)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onJoin) {
                    Text(stringResource(R.string.settings_group_dialog_scan))
                }
            }
        },
    )
}

/** 起主机前问一下家庭组叫什么名字，因为这是家人唯一能看到的名字 */
@Composable
fun HostNameDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val defaultName = stringResource(R.string.settings_default_group_name)
    var name by remember(show) { mutableStateOf(defaultName) }
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_host_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_host_name_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_host_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { defaultName }) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.settings_host_name_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
