package io.github.Calvariaa.kitchennotes.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.Calvariaa.kitchennotes.KitchenNotesApp
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.ui.DishViewModel
import io.github.Calvariaa.kitchennotes.ui.components.SoftBox
import io.github.Calvariaa.kitchennotes.ui.i18n.text

/**
 * 新建菜谱页。
 *
 * 交互设计：
 *  - 图片是可多选的横向缩略图条，每张右上角有删除按钮。来源有两个：
 *    Photo Picker（系统相册）和相机（拍照后落到 FileProvider 的临时文件）。
 *  - 文字是一个多行输入框，提示里直接举例「小红书截图转发过来的文字、菜单、语音转文字都行」，
 *    降低用户「不知道该粘什么」的犹豫。
 *  - 「来源」是可选的单行标签，只影响 AI 的解读提示和入库字段，不填也能生成。
 *  - 生成中显示阶段文案（压缩图片 → 调模型 → 解析），因为一次要 20-60 秒，
 *    没有阶段反馈用户会以为卡死。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CreateRecipeScreen(
    vm: DishViewModel,
    onBack: () -> Unit,
    onGenerated: () -> Unit,
) {
    val generating by vm.generating.collectAsState()
    val stage by vm.generateStage.collectAsState()
    val error by vm.generateError.collectAsState()
    val draft by vm.draft.collectAsState()

    val images = remember { mutableStateListOf<Uri>() }
    var text by remember { mutableStateOf("") }
    var titleHint by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("") }
    /* 拍照要先把目标 Uri 交给相机，回来才知道成没成 —— 必须是 compose 状态，
       用文件顶层的 var 会在重组/返回栈里串台 */
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
    ) { uris ->
        uris.forEach { if (!images.contains(it)) images.add(it) }
    }

    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        // 用户取消拍照时文件已经建出来了，顺手删掉，免得 cache 里堆空图
        val uri = pendingCameraUri
        if (success && uri != null) {
            if (!images.contains(uri)) images.add(uri)
        } else if (uri != null) {
            runCatching {
                android.net.Uri.parse(uri.toString()).path?.let { java.io.File(it).delete() }
            }
        }
        pendingCameraUri = null
    }

    // 生成成功后自动进预览。必须在 effect 里做：在组合过程中直接导航会在
    // 同一帧里改状态，Compose 会抛 "composing while navigating"。
    LaunchedEffect(draft?.id) {
        if (draft != null) onGenerated()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_title), fontWeight = FontWeight.SemiBold) },
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.create_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- 图片 ----
            SoftBox {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.create_photos_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${images.size}/9",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (images.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(images) { index, uri ->
                                Box {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(88.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp),
                                        onClick = { images.removeAt(index) },
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.padding(3.dp),
                                        )
                                    }
                                    if (index == 0) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(4.dp),
                                        ) {
                                            Text(
                                                stringResource(R.string.create_cover_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = {
                                pickImages.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            enabled = images.size < 9,
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.create_pick_gallery))
                        }
                        FilledTonalButton(
                            onClick = {
                                val ctx = KitchenNotesApp.instance
                                val uri = createCameraUri(ctx)
                                pendingCameraUri = uri
                                takePicture.launch(uri)
                            },
                            enabled = images.size < 9,
                        ) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.create_take_photo))
                        }
                    }
                }
            }

            // ---- 文字 ----
            SoftBox {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.create_text_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        placeholder = {
                            Text(stringResource(R.string.create_text_placeholder))
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }

            // ---- 可选提示 ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = titleHint,
                    onValueChange = { titleHint = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.create_label_title_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = platform,
                    onValueChange = { platform = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.create_label_source)) },
                    placeholder = { Text(stringResource(R.string.create_source_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }

            // ---- 快速填来源 ----
            // 预设值也是界面文案，从资源里取；写进菜谱的 sourcePlatform 字段是数据，
            // 所以英文界面下会存 "Xiaohongshu"，与用户手动输入的英文保持一致
            val sourcePresets = stringArrayResource(R.array.create_source_presets)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sourcePresets.forEach { p ->
                    AssistChip(
                        onClick = { platform = p },
                        label = { Text(p, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }

            // ---- 错误 ----
            AnimatedVisibility(error != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = error?.text().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ---- 生成按钮 ----
            Button(
                onClick = { vm.generate(images.toList(), text, titleHint, platform) },
                enabled = !generating && (images.isNotEmpty() || text.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stage?.text() ?: stringResource(R.string.create_generating))
                } else {
                    Text(
                        stringResource(R.string.create_generate),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if (generating) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50)),
                )
            }

            TextButton(
                onClick = { vm.clearAiTest() },
                modifier = Modifier.height(0.dp),
            ) {}

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 拍照落地路径。
 *
 * 用了 FileProvider（manifest 里声明），因为 Android 7+ 不允许把 file:// 直接丢给相机。
 * 文件放在 cache 目录，压缩入库后即可清理。
 */
private fun createCameraUri(ctx: android.content.Context): Uri {
    val dir = java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }
    val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        ctx,
        "${ctx.packageName}.fileprovider",
        file,
    )
}
