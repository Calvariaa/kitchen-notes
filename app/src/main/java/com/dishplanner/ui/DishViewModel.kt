package com.dishplanner.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dishplanner.AppGraph
import com.dishplanner.ai.AiError
import com.dishplanner.ai.DeepSeekClient
import com.dishplanner.ai.ImagePayload
import com.dishplanner.ai.RecipeAssembler
import com.dishplanner.ai.RecipeJsonRepair
import com.dishplanner.data.AppSettings
import com.dishplanner.data.DiscoveredHost
import com.dishplanner.data.FamilyGroup
import com.dishplanner.data.ImageIngestor
import com.dishplanner.data.Paths
import com.dishplanner.data.Recipe
import com.dishplanner.data.UserIdentity
import com.dishplanner.data.sharedJson
import com.dishplanner.sync.GitServerHost
import com.dishplanner.sync.MembersApi
import com.dishplanner.sync.SshKeyFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局状态。单例（由 Activity 持有并复用同一个实例）。
 *
 * 状态设计：每个「会变的远端数据」一个 StateFlow，UI 只 collect 不持有。
 * 从详情页返回列表时不需要手动刷新 —— 列表订阅的是同一个 flow。
 */
class DishViewModel(private val app: AppGraph) : ViewModel() {

    // ---------------------------------------------------------------- 启动引导

    private val _identity = MutableStateFlow<UserIdentity?>(null)
    val identity: StateFlow<UserIdentity?> = _identity.asStateFlow()

    private val _booted = MutableStateFlow(false)
    val booted: StateFlow<Boolean> = _booted.asStateFlow()

    // ---------------------------------------------------------------- 菜谱

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    var searchQuery by mutableStateOf("")
        private set
    var activeTag by mutableStateOf<String?>(null)
        private set

    /** 设置在「加入家庭组」按钮上，导航层据此跳页；用完即重置 */
    var joinFlow by mutableStateOf(false)

    val allTags: List<String>
        get() = _recipes.value.flatMap { it.tags }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(12)

    val visibleRecipes: List<Recipe>
        get() {
            val q = searchQuery.trim().lowercase()
            return _recipes.value.filter { r ->
                (activeTag == null || r.tags.contains(activeTag)) &&
                    (q.isBlank() || r.searchBlob().contains(q))
            }
        }

    // ---------------------------------------------------------------- 设置与同步

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _group = MutableStateFlow<FamilyGroup?>(null)
    val group: StateFlow<FamilyGroup?> = _group.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val discovered: StateFlow<List<DiscoveredHost>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _hostAddresses = MutableStateFlow<List<String>>(emptyList())
    val hostAddresses: StateFlow<List<String>> = _hostAddresses.asStateFlow()

    private val _hostRunning = MutableStateFlow(false)
    val hostRunning: StateFlow<Boolean> = _hostRunning.asStateFlow()

    // ---------------------------------------------------------------- 生成

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _generateStage = MutableStateFlow("")
    val generateStage: StateFlow<String> = _generateStage.asStateFlow()

    private val _generateError = MutableStateFlow<String?>(null)
    val generateError: StateFlow<String?> = _generateError.asStateFlow()

    private val _draft = MutableStateFlow<Recipe?>(null)
    val draft: StateFlow<Recipe?> = _draft.asStateFlow()

    private val _aiTestResult = MutableStateFlow<String?>(null)
    val aiTestResult: StateFlow<String?> = _aiTestResult.asStateFlow()

    // ---------------------------------------------------------------- 启动

    fun boot() {
        if (_booted.value) return
        viewModelScope.launch {
            _identity.value = app.identity()
            _settings.value = app.settings.get()
            _group.value = app.settings.group()
            _identity.value?.let { id ->
                withContext(Dispatchers.IO) { runCatching { app.gitRepo.ensureInitialized(id) } }
            }
            reload()
            _loading.value = false
            _booted.value = true

            // 之前是主机的话，开机自动把服务拉起来，否则家庭组会「掉线」
            val g = _group.value
            val id = _identity.value
            if (g?.role == FamilyGroup.Role.HOST && id != null) {
                startHost(id)
            }
            if (_settings.value.syncOnLaunch && g?.role == FamilyGroup.Role.MEMBER) {
                runSync(silent = true)
            }
        }
    }

    fun completeOnboarding(name: String, email: String) {
        viewModelScope.launch {
            _loading.value = true
            val id = app.ensureIdentity(name, email)
            _identity.value = id
            _loading.value = false
        }
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { app.recipes.all() }
            _recipes.value = loaded
        }
    }

    fun setQuery(q: String) {
        searchQuery = q
    }

    /** 清空列表页的临时筛选状态，从详情页「返回全部」时用 */
    fun clearFilters() {
        searchQuery = ""
        activeTag = null
    }

    fun setTag(tag: String?) {
        activeTag = if (activeTag == tag) null else tag
    }

    fun getRecipe(id: String): Recipe? = _recipes.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- 编辑

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.recipes.softDelete(recipe.id) }
            reload()
            commitAndPush("recipe:delete ${recipe.id}")
        }
    }

    fun saveEdited(recipe: Recipe) {
        viewModelScope.launch {
            val updated = recipe.copy(updatedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { app.recipes.upsert(updated) }
            reload()
            commitAndPush("recipe:edit ${recipe.id}")
        }
    }

    fun updateRecipe(id: String, transform: (Recipe) -> Recipe) {
        val current = getRecipe(id) ?: return
        saveEdited(transform(current))
    }

    // ---------------------------------------------------------------- 生成菜谱

    /**
     * @param imageUris 用户选的图片（content:// 或 file://）
     * @param text 文字素材
     * @param titleHint 用户指定的菜名，可为空
     * @param platform 来源平台标签
     */
    fun generate(imageUris: List<Uri>, text: String, titleHint: String, platform: String) {
        viewModelScope.launch {
            _generating.value = true
            _generateError.value = null
            _draft.value = null
            try {
                val s = _settings.value

                // 1. 图片入库：压缩 → 内容散列命名 → 写盘，同时准备送模型的 payload
                _generateStage.value = if (imageUris.isEmpty()) "正在整理素材…" else "正在压缩图片（${imageUris.size} 张）…"
                val storedPaths = mutableListOf<String>()
                val payloads = mutableListOf<ImagePayload>()
                withContext(Dispatchers.IO) {
                    imageUris.forEach { uri ->
                        runCatching {
                            val raw = if (uri.scheme == "file") {
                                val p = uri.path ?: return@runCatching
                                ImageIngestor.readFile(app.baseContext, File(p))
                            } else {
                                ImageIngestor.readBytes(app.baseContext, uri)
                            }
                            val normalized = ImageIngestor.normalize(raw, s.imageMaxEdge)
                            storedPaths += app.recipes.storeImage(normalized, "jpg")
                            if (s.sendImagesToModel) {
                                payloads += ImagePayload(normalized, "image/jpeg")
                            }
                        }
                    }
                }

                if (storedPaths.isEmpty() && text.isBlank()) {
                    _generateError.value = "至少给一张图，或者一段文字"
                    return@launch
                }

                // 2. 调模型
                _generateStage.value = "正在让 AI 整理菜谱…"
                val client = DeepSeekClient(s)
                val raw = client.generateRecipe(
                    rawText = text,
                    userTitleHint = titleHint,
                    platformHint = platform,
                    images = payloads.take(s.maxImagesPerRequest),
                )

                // 3. 解析
                _generateStage.value = "正在解析结果…"
                val obj = RecipeJsonRepair.parse(raw)
                val identity = _identity.value
                val recipe = RecipeAssembler.toRecipe(
                    obj = obj,
                    id = app.recipes.newId(),
                    coverImage = storedPaths.firstOrNull().orEmpty(),
                    images = storedPaths,
                    sourceText = text,
                    sourcePlatform = platform,
                    author = identity?.name.orEmpty(),
                    authorEmail = identity?.email.orEmpty(),
                )
                _draft.value = recipe
                _generateStage.value = ""
            } catch (e: AiError) {
                _generateError.value = e.message
                _generateStage.value = ""
            } catch (t: Throwable) {
                _generateError.value = t.message ?: "生成失败"
                _generateStage.value = ""
            } finally {
                _generating.value = false
            }
        }
    }

    /** 让 AI 按一句话要求重写当前草稿 */
    fun refineDraft(instruction: String) {
        val current = _draft.value ?: return
        viewModelScope.launch {
            _generating.value = true
            _generateError.value = null
            _generateStage.value = "正在按你的要求重写…"
            try {
                val currentJson = withContext(Dispatchers.IO) {
                    sharedJson.encodeToString(Recipe.serializer(), current)
                }
                val raw = DeepSeekClient(_settings.value).refineRecipe(currentJson, instruction)
                val obj = RecipeJsonRepair.parse(raw)
                val rebuilt = RecipeAssembler.toRecipe(
                    obj = obj,
                    id = current.id,
                    coverImage = current.coverImage,
                    images = current.images,
                    sourceText = current.sourceText,
                    sourcePlatform = current.sourcePlatform,
                    author = current.author,
                    authorEmail = current.authorEmail,
                    now = current.createdAt,
                )
                _draft.value = rebuilt.copy(updatedAt = System.currentTimeMillis())
                _generateStage.value = ""
            } catch (t: Throwable) {
                _generateError.value = t.message ?: "改写失败"
                _generateStage.value = ""
            } finally {
                _generating.value = false
            }
        }
    }

    fun commitDraft(recipe: Recipe) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.recipes.upsert(recipe) }
            _draft.value = null
            reload()
            commitAndPush("recipe:add ${recipe.id}")
        }
    }

    fun discardDraft() {
        _draft.value = null
        _generateError.value = null
        _generateStage.value = ""
    }

    fun testAi() {
        viewModelScope.launch {
            _aiTestResult.value = "正在连接…"
            try {
                val reply = DeepSeekClient(_settings.value).ping()
                _aiTestResult.value = "连接成功：${reply.take(60)}"
            } catch (t: Throwable) {
                _aiTestResult.value = "连接失败：${t.message}"
            }
        }
    }

    fun clearAiTest() {
        _aiTestResult.value = null
    }

    // ---------------------------------------------------------------- 设置

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { app.settings.updateSuspend(transform) }
            _settings.value = next
        }
    }

    // ---------------------------------------------------------------- 家庭组

    private fun server(): GitServerHost = app.gitServer ?: synchronized(this) {
        app.gitServer ?: GitServerHost(
            app.baseContext, app.settings, app.hostRepository, app.recipes, app.gitRepo,
        ).also { app.gitServer = it }
    }

    fun startHost(id: UserIdentity, groupName: String = "我家厨房") {
        viewModelScope.launch {
            _syncMessage.value = "正在启动主机服务…"
            val srv = server()
            srv.setGroupName(groupName)
            srv.start(id)
            _hostRunning.value = srv.running
            if (srv.running) {
                val meta = app.hostRepository.readMeta()
                val port = _settings.value.serverPort
                val addresses = srv.localAddresses()
                _hostAddresses.value = addresses.map { "$it:$port" }
                val g = FamilyGroup(
                    name = meta.groupName,
                    role = FamilyGroup.Role.HOST,
                    // 只用于本地展示。成员实际连的是扫描出来的地址，
                    // 多网卡时（WiFi + 热点）第一个未必是家人所在的那段网
                    hostAddress = _hostAddresses.value.firstOrNull() ?: "127.0.0.1:$port",
                    token = meta.token,
                    joinedAt = _group.value?.joinedAt ?: System.currentTimeMillis(),
                )
                app.settings.saveGroup(g)
                _group.value = g
                withContext(Dispatchers.IO) {
                    app.gitRepo.commitAll(id, "recipe:host-init")
                    srv.pushWorkspaceToBare(id)
                }
                _syncMessage.value = "主机已启动，同一个 WiFi 下的设备就能扫到你"
            } else {
                _syncMessage.value = srv.lastError ?: "主机启动失败"
            }
        }
    }

    fun stopHost() {
        viewModelScope.launch {
            server().stop()
            _hostRunning.value = false
            _hostAddresses.value = emptyList()
            app.settings.clearGroup()
            _group.value = null
            _syncMessage.value = "已关闭主机，家庭组解散"
        }
    }

    fun scanLan() {
        viewModelScope.launch {
            _scanning.value = true
            _discovered.value = emptyList()
            try {
                _discovered.value = app.lanDiscovery.scan()
            } finally {
                _scanning.value = false
            }
        }
    }

    fun joinGroup(host: DiscoveredHost, token: String) {
        viewModelScope.launch {
            val id = _identity.value ?: return@launch
            try {
                _syncMessage.value = "正在加入「${host.groupName}」…"
                val g = MembersApi(app.settings).join(host.address, token, id)
                app.settings.saveGroup(g)
                _group.value = g
                withContext(Dispatchers.IO) {
                    app.gitRepo.ensureInitialized(id)
                    runCatching { app.syncClient.sync(id) }
                }
                reload()
                _syncMessage.value = "已加入「${g.name}」，菜谱已拉取"
            } catch (t: Throwable) {
                _syncMessage.value = t.message ?: "加入失败"
            }
        }
    }

    fun leaveGroup() {
        viewModelScope.launch {
            val g = _group.value
            val id = _identity.value
            if (g != null && id != null && g.role == FamilyGroup.Role.MEMBER) {
                withContext(Dispatchers.IO) {
                    runCatching { MembersApi(app.settings).leave(g.hostAddress, g.token, id.email) }
                }
            }
            app.settings.clearGroup()
            _group.value = null
            _syncMessage.value = "已退出家庭组"
        }
    }

    fun runSync(silent: Boolean = false) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            if (!silent) _syncMessage.value = "同步中…"
            try {
                val result = withContext(Dispatchers.IO) { app.sync() }
                // 主机路径下 app.sync() 内部已经把成员提交并进工作区，必须重新读盘
                reload()
                _syncMessage.value = result.message
            } catch (t: Throwable) {
                _syncMessage.value = "同步失败：${t.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    /** 本地改动后：提交 + 推送（成员）／推裸仓库（主机） */
    private fun commitAndPush(message: String) {
        viewModelScope.launch {
            val id = _identity.value ?: return@launch
            withContext(Dispatchers.IO) { runCatching { app.gitRepo.commitAll(id, message) } }
            val g = _group.value ?: return@launch
            val s = _settings.value
            if (!s.pushAfterEdit) return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    if (g.role == FamilyGroup.Role.HOST) {
                        app.gitServer?.pushWorkspaceToBare(id)
                    } else {
                        app.syncClient.sync(id)
                    }
                }
            }
            reload()
        }
    }

    fun regenerateSshKey() {
        viewModelScope.launch {
            val current = _identity.value ?: return@launch
            val (priv, pub) = withContext(Dispatchers.Default) {
                SshKeyFactory.generateKeyPair(current.email)
            }
            val updated = current.copy(sshPrivateKeyPem = priv, sshPublicKey = pub)
            app.settings.saveIdentity(updated)
            _identity.value = updated
            _syncMessage.value = "密钥已重新生成，加入新家庭组时会用新公钥登记"
        }
    }

    fun storageSummary(): String {
        val imgDir = Paths.imagesDir(app.baseContext)
        val bytes = runCatching {
            imgDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        return "${_recipes.value.size} 道菜谱 · 图片 ${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }

    fun repoPath(): String = Paths.repo(app.baseContext).absolutePath

    class Factory(private val app: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DishViewModel(app) as T
    }
}
