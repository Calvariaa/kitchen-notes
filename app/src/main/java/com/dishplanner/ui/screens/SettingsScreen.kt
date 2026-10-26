package com.dishplanner.ui.screens

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dishplanner.ai.DeepSeekClient
import com.dishplanner.data.AppSettings
import com.dishplanner.data.FamilyGroup
import com.dishplanner.ui.DishViewModel
import com.dishplanner.ui.components.SectionTitle
import com.dishplanner.ui.components.SoftBox
import com.dishplanner.ui.theme.Palette

/**
 * 设置页。分五块：AI 服务、主题、家庭组同步、身份、数据。
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
    val clipboard = LocalClipboardManager.current

    var apiKeyVisible by remember { mutableStateOf(false) }
    var showJoinChoice by remember { mutableStateOf(false) }
    var showHostNameDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
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
            SectionTitle("AI 服务")
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = settings.apiBaseUrl,
                        onValueChange = { v -> vm.updateSettings { it.copy(apiBaseUrl = v) } },
                        label = { Text("API 链接") },
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
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Text(
                                    if (apiKeyVisible) "隐藏" else "显示",
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
                        label = { Text("模型") },
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
                        title = "把照片发给模型识别",
                        subtitle = "关闭后只用文字素材生成，图片仅作展示",
                        checked = settings.sendImagesToModel,
                        onChange = { v -> vm.updateSettings { it.copy(sendImagesToModel = v) } },
                    )
                    SettingSwitch(
                        title = "启用思维链",
                        subtitle = "更慢更贵，但复杂菜式的步骤会更讲得通；一般建议关闭",
                        checked = settings.thinkingEnabled,
                        onChange = { v -> vm.updateSettings { it.copy(thinkingEnabled = v) } },
                    )

                    SliderRow(
                        label = "每次最多发送图片",
                        value = settings.maxImagesPerRequest.toFloat(),
                        range = 1f..9f,
                        steps = 7,
                        display = "${settings.maxImagesPerRequest} 张",
                        onChange = { v -> vm.updateSettings { it.copy(maxImagesPerRequest = v.toInt()) } },
                    )
                    SliderRow(
                        label = "图片长边压缩到",
                        value = settings.imageMaxEdge.toFloat(),
                        range = 800f..2400f,
                        steps = 7,
                        display = "${settings.imageMaxEdge} px",
                        onChange = { v ->
                            // 吸附到 200 的整数倍，避免存储里出现 1837 这种怪值
                            val snapped = (v / 200).toInt() * 200
                            vm.updateSettings { it.copy(imageMaxEdge = snapped.coerceIn(800, 2400)) }
                        },
                    )

                    OutlinedTextField(
                        value = settings.promptAddon,
                        onValueChange = { v -> vm.updateSettings { it.copy(promptAddon = v) } },
                        label = { Text("追加提示词（可选）") },
                        placeholder = { Text("例如：不要辣；家里只有空气炸锅；份量按 4 人写") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                        shape = RoundedCornerShape(14.dp),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.testAi() },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("测试连接") }
                    }

                    AnimatedVisibility(aiResult != null) {
                        Text(
                            text = aiResult.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (aiResult?.startsWith("连接成功") == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }

            // ==================== 主题 ====================
            SectionTitle("主题")
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "配色",
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
                        title = "跟随系统壁纸动态取色",
                        subtitle = "Android 12+ 可用；开启后忽略上面的配色",
                        checked = settings.dynamicColor,
                        onChange = { v -> vm.updateSettings { it.copy(dynamicColor = v) } },
                    )

                    Text(
                        "明暗",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
                            .forEach { (id, label) ->
                                FilterChip(
                                    selected = settings.themeMode == id,
                                    onClick = { vm.updateSettings { it.copy(themeMode = id) } },
                                    label = { Text(label) },
                                    shape = RoundedCornerShape(10.dp),
                                )
                            }
                    }
                }
            }

            // ==================== 家庭组 ====================
            SectionTitle("家庭组同步")
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "同一 WiFi 下，一台设备当主机，其他设备加入后菜谱会自动双向同步。" +
                            "底层是真的 git 仓库：一道菜一个文件，谁加的、什么时候改的都留在提交记录里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val g = group
                    when (g?.role) {
                        FamilyGroup.Role.HOST -> {
                            Text(
                                "你在当主机" + if (hostRunning) "（运行中）" else "（已停止）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (hostAddresses.isNotEmpty()) {
                                Text(
                                    "其他设备扫到你的地址：",
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
                                                contentDescription = "复制",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                "加入码：${g.token}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { identity?.let { vm.startHost(it, g.name) } },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text("重启服务") }
                                TextButton(onClick = { vm.stopHost() }) { Text("解散家庭组") }
                            }
                        }

                        FamilyGroup.Role.MEMBER -> {
                            Text(
                                "已加入「${g.name}」",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "主机：${g.hostAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { vm.runSync() },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text("立即同步") }
                                TextButton(onClick = { vm.leaveGroup() }) { Text("退出家庭组") }
                            }
                        }

                        else -> {
                            Text(
                                "还没加入家庭组。你可以自己当主机，或者扫一下局域网里的主机。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { showHostNameDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text("当主机") }
                                OutlinedButton(
                                    onClick = { showJoinChoice = true },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text("加入家庭组") }
                            }
                        }
                    }

                    AnimatedVisibility(syncMessage != null) {
                        Text(
                            syncMessage.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    SettingSwitch(
                        title = "启动时自动拉取",
                        subtitle = null,
                        checked = settings.syncOnLaunch,
                        onChange = { v -> vm.updateSettings { it.copy(syncOnLaunch = v) } },
                    )
                    SettingSwitch(
                        title = "改完立即推送",
                        subtitle = null,
                        checked = settings.pushAfterEdit,
                        onChange = { v -> vm.updateSettings { it.copy(pushAfterEdit = v) } },
                    )
                }
            }

            // ==================== 身份 ====================
            SectionTitle("我的身份")
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
                            "SSH 公钥指纹",
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
                                Text("复制公钥")
                            }
                            TextButton(onClick = { vm.regenerateSshKey() }) {
                                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重新生成")
                            }
                        }
                    }
                }
            }

            // ==================== 数据 ====================
            SectionTitle("数据")
            SoftBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        vm.storageSummary(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        vm.repoPath(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "菜谱以 JSON 存在这里，图片按内容散列命名；这个目录本身就是一个 git 仓库，可以直接拷走。",
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
                palette.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = t.onSurface.copy(alpha = alpha),
            )
            Text(
                palette.description,
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
        title = { Text("家庭组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "当主机：本机保存所有菜谱，并开一个局域网服务让家人连进来。\n" +
                        "加入：扫描同一个 WiFi 下已经开好主机的设备。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onHost) { Text("当主机") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = onJoin) { Text("去扫描加入") }
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
    var name by remember { mutableStateOf("我家厨房") }
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给家庭组起个名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "家人扫描时看到的就是这个名字。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("家庭组名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "我家厨房" }) }, enabled = name.isNotBlank()) {
                Text("开始当主机")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
