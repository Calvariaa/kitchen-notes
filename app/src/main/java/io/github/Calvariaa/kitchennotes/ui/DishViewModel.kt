package io.github.Calvariaa.kitchennotes.ui

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.Calvariaa.kitchennotes.AppGraph
import io.github.Calvariaa.kitchennotes.BuildConfig
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.ai.AiError
import io.github.Calvariaa.kitchennotes.ai.DeepSeekClient
import io.github.Calvariaa.kitchennotes.ai.ImagePayload
import io.github.Calvariaa.kitchennotes.ai.RecipeAssembler
import io.github.Calvariaa.kitchennotes.ai.RecipeJsonRepair
import io.github.Calvariaa.kitchennotes.data.AppSettings
import io.github.Calvariaa.kitchennotes.data.CookedRecord
import io.github.Calvariaa.kitchennotes.data.ImageIngestor
import io.github.Calvariaa.kitchennotes.data.MealSlot
import io.github.Calvariaa.kitchennotes.data.Menu
import io.github.Calvariaa.kitchennotes.data.MenuItem
import io.github.Calvariaa.kitchennotes.data.MeshGroup
import io.github.Calvariaa.kitchennotes.data.Paths
import io.github.Calvariaa.kitchennotes.data.Peer
import io.github.Calvariaa.kitchennotes.data.Recipe
import io.github.Calvariaa.kitchennotes.data.RecipeSort
import io.github.Calvariaa.kitchennotes.data.Staple
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import io.github.Calvariaa.kitchennotes.data.sharedJson
import io.github.Calvariaa.kitchennotes.sync.ConflictReport
import io.github.Calvariaa.kitchennotes.sync.DiscoveredNode
import io.github.Calvariaa.kitchennotes.sync.GitLock
import io.github.Calvariaa.kitchennotes.sync.MeshApi
import io.github.Calvariaa.kitchennotes.sync.MeshCrypto
import io.github.Calvariaa.kitchennotes.sync.SshKeyFactory
import io.github.Calvariaa.kitchennotes.sync.SyncError
import io.github.Calvariaa.kitchennotes.ui.i18n.UiText
import io.github.Calvariaa.kitchennotes.ui.i18n.toUiText
import io.github.Calvariaa.kitchennotes.update.UpdateError
import io.github.Calvariaa.kitchennotes.update.UpdateInfo
import io.github.Calvariaa.kitchennotes.update.UpdateInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 菜单里聚合后的一行材料：名字 + 各道菜给出的用量（去重后并列） */
data class MenuIngredient(
    val name: String,
    val amounts: List<String>,
)

/**
 * 全局状态。单例（由 Activity 持有并复用同一个实例）。
 *
 * 状态设计：每个「会变的远端数据」一个 StateFlow，UI 只 collect 不持有。
 * 从详情页返回列表时不需要手动刷新 —— 列表订阅的是同一个 flow。
 *
 * i18n 约定：所有会展示给用户的文案都是 [UiText]，不是 String。
 * 原因见 UiText 的注释 —— ViewModel 在语言切换前产出的 String 不会跟着变。
 */
class DishViewModel(private val app: AppGraph) : ViewModel() {

    // ---------------------------------------------------------------- 启动引导

    private val _identity = MutableStateFlow<UserIdentity?>(null)
    val identity: StateFlow<UserIdentity?> = _identity.asStateFlow()

    private val _booted = MutableStateFlow(false)
    val booted: StateFlow<Boolean> = _booted.asStateFlow()

    /** 引导页的失败原因；null 表示没有错误 */
    private val _onboardingError = MutableStateFlow<UiText?>(null)
    val onboardingError: StateFlow<UiText?> = _onboardingError.asStateFlow()

    // ---------------------------------------------------------------- 菜谱

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    var searchQuery by mutableStateOf("")
        private set
    var activeTag by mutableStateOf<String?>(null)
        private set

    var sort by mutableStateOf(RecipeSort.UPDATED)
        private set

    // ---------------------------------------------------------------- 菜单

    private val _menus = MutableStateFlow<List<Menu>>(emptyList())
    val menus: StateFlow<List<Menu>> = _menus.asStateFlow()

    /** recipeId -> 最近一次被做的日期，列表排序「按最近做过」用 */
    private val _lastCooked = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastCooked: StateFlow<Map<String, String>> = _lastCooked.asStateFlow()

    /** 详情页底部「做过的记录」：当前打开的菜谱的历史 */
    private val _cookedHistory = MutableStateFlow<List<CookedRecord>>(emptyList())
    val cookedHistory: StateFlow<List<CookedRecord>> = _cookedHistory.asStateFlow()

    /** 设置在「加入家庭组」按钮上，导航层据此跳页；用完即重置 */
    var joinFlow by mutableStateOf(false)

    val allTags: List<String>
        get() = tagsOf(_recipes.value)

    val visibleRecipes: List<Recipe>
        get() = visibleRecipesOf(_recipes.value, _lastCooked.value)

    /**
     * 标签榜。**入参必须由调用方传入**，理由见 [visibleRecipesOf]。
     */
    fun tagsOf(all: List<Recipe>): List<String> = all
        .flatMap { it.tags }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }.take(12)

    /**
     * 搜索 + 标签 + 排序三段合成。
     *
     * `recipes` 和 `lastCooked` 是入参而不是内部取 `_recipes.value`：
     * StateFlow 的 `.value` 不是 Compose 快照状态，读它**不会**建立订阅。
     * 之前写成无参 getter 时，新建菜谱 → `_recipes` 更新 → 列表页不重组，
     * 表现为「保存后返回列表看不到新菜，随便点进别的页面再回来才出现」。
     * 现在由 UI 把 `collectAsStateWithLifecycle()` 拿到的列表传进来，
     * 读取发生在组合里，依赖关系就成立了。
     */
    fun visibleRecipesOf(all: List<Recipe>, lastCooked: Map<String, String>): List<Recipe> {
        val q = searchQuery.trim().lowercase()
        val filtered = all.filter { r ->
            (activeTag == null || r.tags.contains(activeTag)) &&
                (q.isBlank() || r.searchBlob().contains(q))
        }
        return sortRecipes(filtered, sort, lastCooked)
    }

    /**
     * 排序。中文按拼音：直接用 [java.text.Collator] 的 CHINA 区域，它认拼音顺序，
     * 而 `sortedBy { it.title }` 是按 UTF-16 code unit 排，中文会变成一堆乱序。
     * 英文界面下同一个 Collator 也能正确排 A-Z（不区分大小写）。
     */
    private fun sortRecipes(
        list: List<Recipe>,
        sort: RecipeSort,
        lastCooked: Map<String, String>,
    ): List<Recipe> = when (sort) {
        RecipeSort.UPDATED -> list.sortedByDescending { it.updatedAt }
        RecipeSort.CREATED -> list.sortedByDescending { it.createdAt }
        // Collator 构造要读 locale 数据，不为每帧新建一个
        RecipeSort.TITLE -> list.sortedWith { a, b -> titleCollator.compare(a.title, b.title) }
        RecipeSort.DIFFICULTY -> list.sortedWith(
            compareBy<Recipe> { it.difficulty }.thenBy { it.title },
        )
        // 没做过的排最后：倒序时给空串，它比任何 yyyy-MM-dd 都小
        RecipeSort.LAST_COOKED -> list.sortedWith(
            compareByDescending<Recipe> { lastCooked[it.id].orEmpty() }
                .thenByDescending { it.updatedAt },
        )
    }

    private val titleCollator: java.text.Collator =
        java.text.Collator.getInstance(java.util.Locale.CHINA).apply {
            strength = java.text.Collator.PRIMARY
        }

    // ---------------------------------------------------------------- 设置与同步

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _group = MutableStateFlow<MeshGroup?>(null)
    val group: StateFlow<MeshGroup?> = _group.asStateFlow()

    /** 家庭成员（对等节点）。含本机，UI 用 [selfNodeId] 区分「这台」 */
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<UiText?>(null)
    val syncMessage: StateFlow<UiText?> = _syncMessage.asStateFlow()

    /**
     * 需要用户过目的冲突清单。非空就弹窗，用户关掉即清空。
     *
     * 为什么不做成「同步时就地询问合并/覆盖」：合并发生在后台线程
     * （`postReceive` 的 daemon 线程、定时同步协程），让它们等用户点按钮
     * 会把整条同步链堵死；而且全互联 P2P 下「谁先合并」取决于谁先点了同步，
     * 弹窗会落到随机一台设备上。所以改成**先自动合并、再把结果告诉用户**，
     * 落败的那份在 `files/store/conflicts/` 里留了实体备份可以找回。
     */
    private val _conflictToShow = MutableStateFlow<ConflictReport?>(null)
    val conflictToShow: StateFlow<ConflictReport?> = _conflictToShow.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    val discovered: StateFlow<List<DiscoveredNode>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 本机对外地址（`ip:port`），给家人手输或展示用 */
    private val _nodeAddresses = MutableStateFlow<List<String>>(emptyList())
    val nodeAddresses: StateFlow<List<String>> = _nodeAddresses.asStateFlow()

    /** 本机服务端是否在监听。加入家庭后恒为 true，没有「主机开了没」的概念 */
    private val _nodeServing = MutableStateFlow(false)
    val nodeServing: StateFlow<Boolean> = _nodeServing.asStateFlow()

    /**
     * 本机 nodeId（SSH 公钥指纹）。UI 用来在成员列表里标出自己。
     *
     * 同 [inviteCodeOf]：不能让 UI 读 `get() = _identity.value` 的属性，
     * 那个读取不进组合依赖（坑 22），身份刚生成完那一下会渲染成空串，
     * 于是「本机」这一条在扫描结果里会显示成一台陌生设备。
     */
    fun selfNodeIdOf(identity: UserIdentity?): String =
        identity?.let { MeshCrypto.nodeId(it.sshPublicKey) }.orEmpty()

    /**
     * 加入码：由家庭密钥派生，全家一致，换设备重装都不变。
     *
     * 永远不要写成 `val inviteCode get() = _group.value...`：StateFlow 的 `.value`
     * 不是 Compose 快照状态，组合里读它不会建立订阅（见 AGENTS.md 坑 22）。
     * 表现是刚创建完家庭那一下码是空的，重启 App 或者随手切个页面才冒出来 ——
     * 因为那一下的重组是别的状态变更顺带触发的。
     * 所以这里是普通函数，由 UI 把 `group` 传进来。
     */
    fun inviteCodeOf(group: MeshGroup?): String =
        group?.secret?.takeIf { it.isNotBlank() }?.let { MeshCrypto.inviteCode(it) }.orEmpty()

    /** 定时同步协程；设置里改了间隔或开关时要重建 */
    private var autoSyncJob: Job? = null

    /**
     * 按 [AppSettings.autoSyncMinutes] 启动（或重启）定时同步。0 = 关。
     *
     * P2P 下每个人做的是同一件事：和所有在线对端交换数据。
     * 没有「主机走另一条路径」的分支了，所以这个协程对所有人行为一致。
     *
     * 为什么放在 ViewModel 而不是 Service：同步的前提是 App 已启动且用户可能在看列表，
     * ViewModel 的生命周期刚好覆盖这个窗口；做成后台 Service 反而要处理
     * 「用户不在前台时要不要唤醒 UI」这种本不存在的问题。
     */
    fun restartAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
        val minutes = _settings.value.autoSyncMinutes
        if (minutes <= 0) return
        if (_group.value == null) return
        autoSyncJob = viewModelScope.launch {
            while (true) {
                delay(minutes * 60_000L)
                // 已经在同步就跳过这一轮，避免两个 JGit 操作撞仓库锁
                if (!_syncing.value) runSync(silent = true)
            }
        }
    }

    // ---------------------------------------------------------------- 生成

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _generateStage = MutableStateFlow<UiText?>(null)
    val generateStage: StateFlow<UiText?> = _generateStage.asStateFlow()

    private val _generateError = MutableStateFlow<UiText?>(null)
    val generateError: StateFlow<UiText?> = _generateError.asStateFlow()

    private val _draft = MutableStateFlow<Recipe?>(null)
    val draft: StateFlow<Recipe?> = _draft.asStateFlow()

    private val _aiTestResult = MutableStateFlow<UiText?>(null)
    val aiTestResult: StateFlow<UiText?> = _aiTestResult.asStateFlow()

    /* UI 需要区分「连接成功」和「连接失败」来选颜色。
       老办法是比对文案前缀（"连接成功"），语言一换就失效。 */
    private val _aiTestOk = MutableStateFlow(false)
    val aiTestOk: StateFlow<Boolean> = _aiTestOk.asStateFlow()

    // ---------------------------------------------------------------- 应用更新

    /** 检查到的新版本；null 表示已是最新或还没查过 */
    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    private val _updateMessage = MutableStateFlow<UiText?>(null)
    val updateMessage: StateFlow<UiText?> = _updateMessage.asStateFlow()

    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    /** 下载进度 0..100；-1 表示没在下载 */
    private val _updateProgress = MutableStateFlow(-1)
    val updateProgress: StateFlow<Int> = _updateProgress.asStateFlow()

    /** 上一次检查的结论是「有新版本」，UI 用它在列表页弹一条横幅 */
    private val _updatePromptShown = MutableStateFlow(false)
    val updatePromptShown: StateFlow<Boolean> = _updatePromptShown.asStateFlow()

    /**
     * 已经下载好、但当时因为「未知来源」未授权而没能安装的 APK。
     * 用户去系统设置开完权限回到 App 时，用它接上原来的流程 —— 否则要用户
     * 再点一次「下载并安装」，而文件其实已经躺在磁盘上了。
     */
    private var pendingApk: File? = null

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

            // 定时同步要等工作区就绪后再起；间隔/开关变了会重建
            restartAutoSync()

            // 已经是某个家的成员：开机就把本机节点服务拉起来（人人都是服务端），
            // 然后同步一轮。这两步顺序不能反 —— 服务不起，对端推不进来；
            // 不先同步，对端上次推的数据要等第一次定时轮询才出现。
            val g = _group.value
            val id = _identity.value
            if (g != null && id != null) {
                startNodeServing(id, g)
                if (_settings.value.syncOnLaunch) runSync(silent = true)
            }
            // 启动时静默查一次新版：只有真有更新才会出横幅，
            // 未命中时不写任何状态，列表页看起来和没有这个功能一样
            if (_settings.value.checkUpdateOnLaunch) {
                checkUpdate(silent = true)
            }
        }
    }

    /**
     * 完成引导。
     *
     * 这里必须自己兜异常：viewModelScope 的协程没有 CoroutineExceptionHandler，
     * 未捕获异常会一路走到默认处理器 → 进程崩溃。而这条路径恰恰是最容易出问题的
     * （RSA 密钥生成、首次建 git 仓库、文件系统读写），在旧机型上任何一个抛错
     * 用户看到的都是「点了开始使用就闪退」，且没有任何可操作的信息。
     *
     * 分两步的好处：身份存盘成功就算成功，git 初始化失败不该把用户挡在门外 ——
     * 菜谱列表不依赖 .git，同步时 ensureInitialized 还会再试一次。
     */
    fun completeOnboarding(name: String, email: String) {
        viewModelScope.launch {
            _loading.value = true
            _onboardingError.value = null
            try {
                val id = app.ensureIdentity(name, email)
                _identity.value = id
            } catch (t: Throwable) {
                _onboardingError.value = UiText.of(
                    R.string.onboarding_err_failed,
                    t.message ?: t::class.java.simpleName,
                )
            } finally {
                _loading.value = false
            }
        }
    }

    fun clearOnboardingError() {
        _onboardingError.value = null
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { app.recipes.all() }
            _recipes.value = loaded
            refreshMenus()
        }
    }

    /** 菜单与「最近做过」映射一起刷新：两者都是同一批文件的派生视图 */
    private suspend fun refreshMenus() {
        val menus = withContext(Dispatchers.IO) { app.menus.all() }
        _menus.value = menus
        refreshDerivedMenus()
    }

    /** 只重算派生视图（最近做过映射），不重读菜单本身 */
    private suspend fun refreshDerivedMenus() {
        _lastCooked.value = withContext(Dispatchers.IO) { app.menus.lastCookedMap() }
    }

    fun changeSort(next: RecipeSort) {
        sort = next
    }

    /** 详情页打开时加载这道菜的烹饪历史 */
    fun loadCookedHistory(recipeId: String) {
        viewModelScope.launch {
            _cookedHistory.value = withContext(Dispatchers.IO) {
                app.menus.cookedRecordsFor(recipeId)
            }
        }
    }

    fun clearCookedHistory() {
        _cookedHistory.value = emptyList()
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

    // ---------------------------------------------------------------- 菜单

    fun getMenu(id: String): Menu? = _menus.value.firstOrNull { it.id == id }

    /** 当天日期 `yyyy-MM-dd`，新建菜单的默认值 */
    fun today(): String = java.time.LocalDate.now().toString()

    /**
     * 菜单的统一写入口：**先改内存，再异步落盘**。
     *
     * 顺序不能反。`_menus` 是所有读菜单的代码（编辑页、材料汇总、getMenu）的唯一真值源，
     * 如果先落盘再回头 reload，中间有一个窗口期读到的是旧数据：
     *  - 连续给两道菜点星标时，第二次会拿第一次之前的 items 做基，把第一次的评分覆盖掉；
     *  - 新建菜单后立刻导航到编辑页，`getMenu` 查不到，页面会先闪一下转圈。
     * 内存先行对这两个问题都是根治。落盘失败时再把内存回滚成盘上的真实状态。
     */
    private fun mutateMenu(
        id: String,
        commitMessage: String? = null,
        transform: (Menu) -> Menu,
    ) {
        val current = getMenu(id) ?: return
        val next = transform(current)
        if (next == current) return

        // 1. 内存先行：之后的任何读取都能看到最新值
        _menus.value = _menus.value
            .map { if (it.id == id) next else it }
            .sortedWith(menuOrder)

        // 2. 异步落盘
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { app.menus.upsert(next) }
            }.isSuccess
            if (!ok) {
                // 写失败就把内存退回磁盘的真实状态，别让用户对着假数据继续操作
                refreshMenus()
                return@launch
            }
            refreshDerivedMenus()
            commitMessage?.let { commitAndPush(it) }
        }
    }

    /** 菜单列表的排序规则，和 [MenuRepository.all] 保持一致 */
    private val menuOrder: Comparator<Menu> =
        compareByDescending<Menu> { it.date }.thenByDescending { it.slot.ordinal }

    fun createMenu(slot: MealSlot, date: String = today()): String {
        val id = app.menus.newId()
        val now = System.currentTimeMillis()
        val menu = Menu(id = id, date = date, slot = slot, createdAt = now, updatedAt = now)
        // 同步入内存，调用方紧接着导航到编辑页时能立刻查到
        _menus.value = (_menus.value + menu).sortedWith(menuOrder)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.menus.upsert(menu) }
            // 新建菜单也要同步，否则另一台设备看不到这顿饭
            commitAndPush("menu:add $id")
        }
        return id
    }

    fun saveMenu(menu: Menu, commitMessage: String? = null) {
        mutateMenu(menu.id, commitMessage) { menu }
    }

    /** 勾选/取消一道菜。新加入的条目从菜谱取标题快照 */
    fun toggleMenuRecipe(menuId: String, recipeId: String) {
        val title = getRecipe(recipeId)?.title.orEmpty()
        mutateMenu(menuId, commitMessage = "menu:edit $menuId") { menu ->
            val next = if (menu.items.any { it.recipeId == recipeId }) {
                menu.items.filterNot { it.recipeId == recipeId }
            } else {
                menu.items + MenuItem(recipeId = recipeId, title = title)
            }
            menu.copy(items = next, updatedAt = System.currentTimeMillis())
        }
    }

    fun setMenuSlot(menuId: String, slot: MealSlot) =
        mutateMenu(menuId, commitMessage = "menu:edit $menuId") { it.copy(slot = slot, updatedAt = System.currentTimeMillis()) }

    fun setMenuDate(menuId: String, date: String) =
        mutateMenu(menuId, commitMessage = "menu:edit $menuId") { it.copy(date = date, updatedAt = System.currentTimeMillis()) }

    fun setMenuNote(menuId: String, note: String) {
        // 备注是自由文本，用户会一个字一个字地敲。走 mutateMenu 会把每个按键都变成
        // 一次「写临时文件 + fd.sync() + rename」，中文输入法下几十毫秒一次，
        // 打字会明显卡顿且疯狂磨损闪存。
        // 所以内存立刻更新（输入框不会掉字），落盘防抖到停顿之后一次。
        val current = getMenu(menuId) ?: return
        val next = current.copy(note = note, updatedAt = System.currentTimeMillis())
        _menus.value = _menus.value.map { if (it.id == menuId) next else it }

        noteSaveJob?.cancel()
        noteSaveJob = viewModelScope.launch {
            delay(NOTE_SAVE_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { app.menus.upsert(next) }
            // 防抖落盘后再提交，避免每个按键都触发一次网络往返
            commitAndPush("menu:note $menuId")
        }
    }

    private var noteSaveJob: Job? = null

    fun toggleMenuStaple(menuId: String, staple: Staple) =
        mutateMenu(menuId, commitMessage = "menu:edit $menuId") { menu ->
            val next = if (menu.staples.contains(staple)) menu.staples - staple else menu.staples + staple
            menu.copy(staples = next, updatedAt = System.currentTimeMillis())
        }

    /**
     * 打分。0 = 取消评分。
     *
     * 走 [mutateMenu] 是为了防连点丢分：用户在几颗星之间连点，
     * 每次都必须基于上一次的结果，而不是同一份旧快照。
     */
    fun rateMenuItem(menuId: String, recipeId: String, rating: Int) {
        mutateMenu(menuId, commitMessage = "menu:rate $menuId") { menu ->
            menu.withItemRating(recipeId, rating, System.currentTimeMillis())
        }
        // 详情页底部「做过的记录」显示的就是这个评分，要跟着刷新
        viewModelScope.launch {
            _cookedHistory.value = withContext(Dispatchers.IO) {
                app.menus.cookedRecordsFor(recipeId)
            }
        }
    }

    fun deleteMenu(menu: Menu) {
        _menus.value = _menus.value.filterNot { it.id == menu.id }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.menus.softDelete(menu.id) }
            refreshDerivedMenus()
            commitAndPush("menu:delete ${menu.id}")
        }
    }

    /**
     * 一顿饭需要的材料，按名字聚合。
     *
     * 同一道菜重复出现（不该发生，勾选是集合语义）和不同菜用同一种料都会合并，
     * 用量不在这里做「300g + 200g = 500g」的算术 —— 单位不统一（克/勺/适量/半个），
     * 算错了比不算更糟。这里只把同名的用量并排列出，让人自己看总量。
     */
    fun menuIngredients(menu: Menu, recipes: List<Recipe>): List<MenuIngredient> {
        val byId = recipes.associateBy { it.id }
        val byName = linkedMapOf<String, MutableList<String>>()
        menu.items.forEach { item ->
            val recipe = byId[item.recipeId] ?: return@forEach
            recipe.ingredients.forEach { ing ->
                val amounts = byName.getOrPut(ing.name.trim()) { mutableListOf() }
                if (ing.amount.isNotBlank()) amounts += ing.amount.trim()
            }
        }
        return byName.map { (name, amounts) ->
            MenuIngredient(name = name, amounts = amounts.distinct())
        }
    }

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
                _generateStage.value = if (imageUris.isEmpty()) {
                    UiText.of(R.string.stage_prepare)
                } else {
                    UiText.of(R.string.stage_compress, imageUris.size)
                }
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
                    _generateError.value = UiText.of(R.string.stage_need_input)
                    _generateStage.value = null
                    return@launch
                }

                // 2. 调模型
                _generateStage.value = UiText.of(R.string.stage_ask)
                val client = DeepSeekClient(s)
                val raw = client.generateRecipe(
                    rawText = text,
                    userTitleHint = titleHint,
                    platformHint = platform,
                    images = payloads.take(s.maxImagesPerRequest),
                )

                // 3. 解析
                _generateStage.value = UiText.of(R.string.stage_parse)
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
                _generateStage.value = null
            } catch (e: AiError) {
                _generateError.value = e.toUiText()
                _generateStage.value = null
            } catch (t: Throwable) {
                _generateError.value = t.message?.takeIf { it.isNotBlank() }
                    ?.let { UiText.raw(it) } ?: UiText.of(R.string.stage_failed)
                _generateStage.value = null
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
            _generateStage.value = UiText.of(R.string.stage_refine)
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
                _generateStage.value = null
            } catch (t: Throwable) {
                _generateError.value = t.message?.takeIf { it.isNotBlank() }
                    ?.let { UiText.raw(it) } ?: UiText.of(R.string.stage_refine_failed)
                _generateStage.value = null
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
        _generateStage.value = null
    }

    // ---------------------------------------------------------------- 更新流程

    /**
     * 检查新版本。
     *
     * @param silent 启动时的静默检查：只在真有新版时出横幅，不显示「已是最新」这类噪音。
     */
    fun checkUpdate(silent: Boolean = false) {
        if (_updateChecking.value) return
        viewModelScope.launch {
            _updateChecking.value = true
            if (!silent) _updateMessage.value = UiText.of(R.string.update_checking)
            try {
                val info = app.updateChecker.fetchLatest(
                    repo = _settings.value.updateRepo,
                    currentVersion = BuildConfig.VERSION_NAME,
                    includePrerelease = _settings.value.updateIncludePrerelease,
                )
                _update.value = info
                if (info != null) {
                    _updatePromptShown.value = true
                    _updateMessage.value = UiText.of(
                        R.string.update_available,
                        info.versionName,
                    )
                } else if (!silent) {
                    _updateMessage.value = UiText.of(
                        R.string.update_current,
                        BuildConfig.VERSION_NAME,
                    )
                }
            } catch (e: UpdateError) {
                if (!silent) _updateMessage.value = e.toUiText()
            } finally {
                _updateChecking.value = false
            }
        }
    }

    fun dismissUpdatePrompt() {
        _updatePromptShown.value = false
    }

    /**
     * 下载并拉起系统安装器。
     *
     * 三个前置条件按顺序处理：
     *  1. 有 UpdateInfo；
     *  2. 「安装未知应用」已授权，否则跳系统设置页而不是硬弹安装（会得到「解析包错误」）；
     *  3. 下载 + 校验成功。
     */
    fun downloadAndInstall() {
        val info = _update.value ?: return
        if (_updateProgress.value >= 0) return // 正在下载

        viewModelScope.launch {
            val ctx = app.baseContext
            if (!UpdateInstaller.canInstall(ctx)) {
                _updateMessage.value = UiText.of(R.string.update_install_blocked)
                UpdateInstaller.requestInstallPermission(ctx)
                return@launch
            }

            _updateProgress.value = 0
            _updateMessage.value = UiText.of(R.string.update_downloading, 0)
            try {
                val apk = app.apkDownloader.download(ctx, info) { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                    _updateProgress.value = pct
                    _updateMessage.value = UiText.of(R.string.update_downloading, pct)
                }
                _updateMessage.value = UiText.of(R.string.update_verifying)
                _updateProgress.value = 100

                val ok = withContext(Dispatchers.Main) { UpdateInstaller.install(ctx, apk) }
                if (ok) {
                    pendingApk = null
                    _updateMessage.value = UiText.of(R.string.update_download_done)
                    // 安装器已经拿起文件，App 可能马上被替换；这里不主动删文件
                } else {
                    // 最常见的原因是未知来源未授权，文件留着等用户授权回来
                    pendingApk = apk
                    _updateMessage.value = UiText.of(R.string.update_install_blocked)
                }
            } catch (e: UpdateError) {
                _updateMessage.value = e.toUiText()
            } finally {
                _updateProgress.value = -1
            }
        }
    }

    /**
     * 用户从「安装未知应用」设置页返回时调用。
     *
     * 如果上次卡在权限上、且现在已授权，就直接接上安装 —— 不重新下载。
     */
    fun resumePendingInstall() {
        val apk = pendingApk ?: return
        val ctx = app.baseContext
        if (!UpdateInstaller.canInstall(ctx)) return
        viewModelScope.launch {
            if (!apk.exists()) {
                pendingApk = null
                return@launch
            }
            val ok = withContext(Dispatchers.Main) { UpdateInstaller.install(ctx, apk) }
            if (ok) {
                pendingApk = null
                _updateMessage.value = UiText.of(R.string.update_download_done)
            }
        }
    }

    fun testAi() {
        viewModelScope.launch {
            _aiTestResult.value = UiText.of(R.string.settings_test_connecting)
            _aiTestOk.value = false
            try {
                val reply = DeepSeekClient(_settings.value).ping()
                _aiTestOk.value = true
                _aiTestResult.value = UiText.of(R.string.settings_test_ok, reply.take(60))
            } catch (t: Throwable) {
                _aiTestOk.value = false
                _aiTestResult.value = UiText.of(
                    R.string.settings_test_failed,
                    t.message.orEmpty(),
                )
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
            // 同步间隔/开关变了，定时协程要按新值重建
            restartAutoSync()
        }
    }

    // ---------------------------------------------------------------- 家庭组
    // ---------------------------------------------------------------- 家庭组（P2P）

    /**
     * 启动本机节点服务。
     *
     * P2P 下这不是「当主机」，而是「把自己挂到网上」—— 加入家庭后，
     * 每台设备都要能被别人连上，否则它只是一个只读消费者，
     * 别人改的东西永远到不了它（除非它主动去拉，而那要靠对方也开着）。
     */
    private suspend fun startNodeServing(id: UserIdentity, group: MeshGroup) {
        val srv = app.meshServer
        srv.onPeerChange = { report ->
            // 对端推完、工作区已合并 → 立刻刷新 UI，用户不用等下次手动同步
            reload()
            viewModelScope.launch { refreshPeers() }
            // 回灌时也可能盖掉了本机刚存的东西。这条路径在后台线程，
            // 不在这里冒泡的话，用户就完全不知道自己的改动被换掉了。
            if (report.needsAttention) _conflictToShow.value = report
        }
        srv.setGroupName(group.name)
        srv.updateGroup(group)
        srv.start(id, group)
        _nodeServing.value = srv.running
        _nodeAddresses.value = if (srv.running) srv.addresses() else emptyList()
        if (!srv.running) {
            _syncMessage.value = srv.lastError?.toUiText()
                ?: UiText.of(R.string.sync_node_failed)
        }
    }

    /** 从磁盘重读成员名册，UI 上的「家庭成员」列表跟它走 */
    suspend fun refreshPeers() {
        withContext(Dispatchers.IO) { runCatching { app.peerRegistry.all() } }
            .onSuccess { _peers.value = it }
    }

    /**
     * 创建家庭：本机成为第一个节点。
     *
     * @param groupName 家庭组名。调用方负责取本地化默认值（Compose 层能拿到 Resources），
     *   ViewModel 不该在这里编一个「我家厨房」—— 英文界面上会露出中文。
     */
    fun createGroup(id: UserIdentity, groupName: String) {
        viewModelScope.launch {
            _syncMessage.value = UiText.of(R.string.sync_node_starting)
            try {
                val group = app.groupManager.create(id, groupName)
                _group.value = group
                app.seedIfEmpty(id)
                startNodeServing(id, group)
                refreshPeers()
                reload()
                // 不要把 startNodeServing 写好的失败原因盖掉。
                // 它设的是「端口 7411 被占用：BindException ...」这种能直接照做的信息，
                // 而这里如果无条件写 sync_group_created / sync_node_failed，用户就只看到
                // 一句「节点启动失败」，既不知道是端口还是权限，也没法自救。
                if (_nodeServing.value && _syncMessage.value == null) {
                    _syncMessage.value = UiText.of(R.string.sync_group_created)
                }
            } catch (t: Throwable) {
                // viewModelScope 没有异常处理器，这里漏一个 catch 就是整个进程闪退（坑 15）
                Log.w(TAG, "createGroup failed", t)
                _nodeServing.value = false
                // 抛到这一层的异常没经过 MeshServer 的 catch，lastError 是空的，
                // 只能把异常本身摆到界面上 —— 否则跟「端口占用」长得一模一样
                _syncMessage.value = UiText.of(
                    R.string.sync_err_unexpected,
                    "${t.javaClass.simpleName}: ${t.message ?: ""}",
                )
            }
        }
    }

    /** 改名。家庭密钥不变 → 加入码不变 → 其他节点不用重新加入 */
    fun renameGroup(name: String) {
        viewModelScope.launch {
            val updated = app.groupManager.rename(name) ?: return@launch
            _group.value = updated
            _syncMessage.value = UiText.of(R.string.sync_group_renamed)
        }
    }

    fun scanLan() {
        viewModelScope.launch {
            _scanning.value = true
            _discovered.value = emptyList()
            try {
                // 这里读 _identity.value 是对的：它在协程里、不在组合里，
                // 只需要「此刻的值」，不需要建立订阅
                _discovered.value = app.lanDiscovery.scan(selfNodeIdOf(_identity.value))
            } catch (t: Throwable) {
                Log.w(TAG, "scan failed", t)
                _discovered.value = emptyList()
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * 加入家庭。申请方交出加入码，对端验过之后把家庭密钥用**我的公钥**封好回给我。
     *
     * 加入成功后本机立刻变成完整节点（也开服务、也能被别人连），
     * 而不是像旧模型那样永远当只读成员。
     */
    fun joinGroup(target: DiscoveredNode, inviteCode: String) {
        viewModelScope.launch {
            val id = _identity.value ?: return@launch
            try {
                _syncMessage.value = UiText.of(R.string.sync_joining, target.groupName)
                val outcome = app.groupManager.join(id, target, inviteCode)
                _group.value = outcome.group
                _peers.value = outcome.peers
                startNodeServing(id, outcome.group)
                refreshPeers()
                reload()
                // 同 createGroup：节点没起来时保留 startNodeServing 给出的具体原因
                if (_nodeServing.value) {
                    _syncMessage.value = UiText.of(R.string.sync_joined, outcome.group.name)
                }
            } catch (e: SyncError) {
                _syncMessage.value = e.toUiText()
            } catch (t: Throwable) {
                Log.w(TAG, "joinGroup failed", t)
                _syncMessage.value = t.message?.takeIf { it.isNotBlank() }
                    ?.let { UiText.raw(it) } ?: UiText.of(R.string.sync_join_failed)
            }
        }
    }

    /**
     * 离开家庭。**保留本地菜谱** —— 用户可能只是想换个组或误触，
     * 删数据是不可逆的伤害，而重建一个家庭只要再点一次。
     */
    fun leaveGroup() {
        viewModelScope.launch {
            runCatching { app.groupManager.leave() }
            _group.value = null
            _peers.value = emptyList()
            _nodeServing.value = false
            _nodeAddresses.value = emptyList()
            autoSyncJob?.cancel()
            autoSyncJob = null
            _syncMessage.value = UiText.of(R.string.sync_left)
        }
    }

    /** 从名册里移除一个成员（不删对方数据，只是不再和它同步） */
    fun forgetPeer(nodeId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { app.peerRegistry.remove(nodeId) } }
            refreshPeers()
        }
    }

    fun runSync(silent: Boolean = false) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            if (!silent) _syncMessage.value = UiText.of(R.string.sync_in_progress)
            try {
                val result = withContext(Dispatchers.IO) { app.sync(silent) }
                // 同步可能把对端的提交合并进工作区，必须重新读盘
                reload()
                refreshPeers()
                // 静默（定时）同步只在真有新数据或冲突时才弹提示，免得每 5 分钟刷一条
                val notable = result.pulledCommits > 0 || result.conflictsResolved > 0
                if (!silent || notable) {
                    _syncMessage.value = result.parts.toUiText(result)
                }
                // 静默同步也要弹冲突：那是「你的改动被盖了」，不是「有数据更新」，
                // 不能用同一个「别打扰」标准对待。
                if (result.conflictReport.needsAttention) _conflictToShow.value = result.conflictReport
            } catch (e: SyncError) {
                // 静默时网络错误不打扰用户（比如这会儿不在同一个 WiFi 下）
                if (!silent) _syncMessage.value = e.toUiText()
            } catch (t: Throwable) {
                if (!silent) _syncMessage.value = UiText.of(R.string.sync_failed, t.message.orEmpty())
            } finally {
                _syncing.value = false
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    /** 用户关掉冲突弹窗。只清展示状态，磁盘上的备份一直留着。 */
    fun dismissConflict() {
        _conflictToShow.value = null
    }

    /**
     * 本地改动后：提交 + 推给自己和其他节点。
     *
     * 每个用户动作都必须走这里。漏掉一个入口，那类改动就只落在本地磁盘、
     * 不进 git，直到下一次全量同步才补上（坑 24 的反例是 createMenu）。
     * P2P 下后果更深：别人那边看起来就是「这条记录凭空消失」。
     */
    private fun commitAndPush(message: String) {
        viewModelScope.launch {
            val id = _identity.value ?: return@launch
            val g = _group.value
            // commit / 镜像 / 同步三步必须整体持锁：postReceive 的后台回灌和定时同步
            // 都在同一个仓库上动 index.lock，不串行化就会有人静默失败（坑 38）
            withContext(Dispatchers.IO) {
                GitLock.mutex.withLock {
                    runCatching { app.gitRepo.commitAll(id, message) }
                    // 没加家庭也要镜像到本机裸仓库：之后建组时数据才现成可被拉取。
                    // 镜像前可能先触发一次回灌（裸仓库里有工作区还没见过的提交时），
                    // 那次合并要署名，所以这里先把它带上。
                    app.meshRepo.identitySnapshot = id
                    runCatching { app.meshRepo.pushWorkspaceToBare() }
                }
            }
            if (g == null) return@launch
            if (!_settings.value.pushAfterEdit) return@launch
            // syncAll 自己会加锁，这里不能再套一层（Mutex 不可重入）
            withContext(Dispatchers.IO) {
                runCatching { app.sync(silent = true) }
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
            _syncMessage.value = UiText.of(R.string.sync_key_regenerated)
        }
    }

    /** 图片目录占用（MB），设置页展示用。格式化成字符串的工作交给 UI */
    fun imagesUsedMb(): Double {
        val imgDir = Paths.imagesDir(app.baseContext)
        val bytes = runCatching {
            imgDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        return bytes / 1024.0 / 1024.0
    }

    /**
     * 设置页「数据」块的摘要：菜谱条数 + 图片占用。
     *
     * 返回 [UiText] 而不是拼好的字符串 —— 「N 道菜 · 图片 X MB」中间的连接符
     * 中英文不同，必须留到渲染时按当前语言拼。
     */
    fun storageSummary(): UiText {
        val count = _recipes.value.size
        val mb = imagesUsedMb()
        return UiText.of(R.string.settings_storage_value, count, String.format("%.1f", mb))
    }

    fun repoPath(): String = Paths.repo(app.baseContext).absolutePath

    class Factory(private val app: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DishViewModel(app) as T
    }

    private companion object {
        /** 备注落盘防抖：等到用户停顿这么久再写一次 */
        const val NOTE_SAVE_DEBOUNCE_MS = 500L
        const val TAG = "DishViewModel"
    }
}
