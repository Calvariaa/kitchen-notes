package io.github.Calvariaa.kitchennotes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 全部用户可改的设置。可调字段在设置页逐项暴露。 */
@Serializable
data class AppSettings(
    // ---------- AI ----------
    val apiBaseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    /** 模型名；DeepSeek 官方现在是 deepseek-flash（含视觉）与 deepseek-v4-pro */
    val model: String = "deepseek-flash",
    /** 思维链模式开关；菜谱生成用 disabled 更快更省 */
    val thinkingEnabled: Boolean = false,
    val reasoningEffort: String = "high",
    /** 是否把用户上传的照片一并发给模型识别 */
    val sendImagesToModel: Boolean = true,
    /** 每次最多发几张图，控制 token 成本 */
    val maxImagesPerRequest: Int = 4,
    /** 单张图发送前压缩到的最长边像素 */
    val imageMaxEdge: Int = 1400,
    /** 生成温度 */
    val temperature: Double = 1.0,
    /** 内置提示词的额外补充（用户可在设置里追加自己的偏好） */
    val promptAddon: String = "",
    /** 是否用内置提示词模板（关掉则完全走 promptAddon） */
    val useBuiltinPrompt: Boolean = true,

    // ---------- 主题 ----------
    /** 主题配色 id，见 ui/theme/Palette.kt */
    val paletteId: String = "terracotta",
    /** 明暗模式：system / light / dark */
    val themeMode: String = "system",
    /** 是否使用 Android 12+ 动态取色（会覆盖 paletteId 的色相，保留版式） */
    val dynamicColor: Boolean = false,
    /**
     * 界面语言：system / zh / en。
     * 默认跟随系统而不是写死 zh —— 菜谱内容是中文的，但界面语言该跟用户走，
     * 否则英文系统上拿到一个全中文界面会显得 i18n 没做完。
     */
    val language: String = "system",

    // ---------- 同步 ----------
    /** 主机对外服务端口 */
    val serverPort: Int = 7411,
    /** 自动同步间隔（分钟），0 表示不自动 */
    val autoSyncMinutes: Int = 5,
    /** 启动时自动拉取 */
    val syncOnLaunch: Boolean = true,
    /** 离开编辑页后立即推送 */
    val pushAfterEdit: Boolean = true,

    // ---------- 应用更新 ----------
    /**
     * 更新来源仓库（owner/repo）。默认指向本项目的 GitHub 仓库，
     * CI 会把 release APK 传到这儿的 Releases。
     */
    val updateRepo: String = "Calvariaa/kitchen-notes",
    /** 启动时静默检查一次新版本 */
    val checkUpdateOnLaunch: Boolean = true,
    /** 是否把预发布版（tag 里带 -rc / -dev）也当作可更新目标 */
    val updateIncludePrerelease: Boolean = false,
) {
    val normalizedBaseUrl: String
        get() = apiBaseUrl.trim().trimEnd('/').ifBlank { "https://api.deepseek.com" }
}

/**
 * 设置的持久化。用 JSON 文件而不是 DataStore：设置项多、要整体导入导出、要跟着备份走，
 * 单文件读写更直白，也方便「导出/导入配置」这类功能。
 */
class SettingsStore(private val ctx: Context) {

    private val file: File get() = File(Paths.root(ctx), "settings.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cache: AppSettings? = null

    fun get(): AppSettings {
        cache?.let { return it }
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<AppSettings>(file.readText()) else AppSettings()
        }.getOrDefault(AppSettings())
        cache = loaded
        return loaded
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(get())
        cache = next
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(AppSettings.serializer(), next))
        }
        return next
    }

    suspend fun updateSuspend(transform: (AppSettings) -> AppSettings): AppSettings =
        withContext(Dispatchers.IO) { update(transform) }

    // ---------------- 身份（SSH key 在这里按需生成） ----------------

    private val identityFile: File get() = File(Paths.root(ctx), "identity.json")

    fun identity(): UserIdentity? = runCatching {
        if (identityFile.exists())
            json.decodeFromString<UserIdentity>(identityFile.readText()) else null
    }.getOrNull()

    suspend fun saveIdentity(identity: UserIdentity) = withContext(Dispatchers.IO) {
        identityFile.parentFile?.mkdirs()
        identityFile.writeText(json.encodeToString(UserIdentity.serializer(), identity))
    }

    // ---------------- 家庭组 ----------------

    private val groupFile: File get() = File(Paths.root(ctx), "family.json")

    fun group(): MeshGroup? = runCatching {
        if (groupFile.exists()) json.decodeFromString<MeshGroup>(groupFile.readText()) else null
    }.getOrNull()?.takeIf { it.isConfigured }

    suspend fun saveGroup(group: MeshGroup) = withContext(Dispatchers.IO) {
        groupFile.parentFile?.mkdirs()
        groupFile.writeText(json.encodeToString(MeshGroup.serializer(), group))
    }

    suspend fun clearGroup() = withContext(Dispatchers.IO) {
        groupFile.delete()
        Unit
    }

    // ---------------- 已知节点 ----------------

    private val peersFile: File get() = File(Paths.root(ctx), "peers.json")

    fun peers(): List<Peer> = runCatching {
        if (peersFile.exists()) json.decodeFromString<List<Peer>>(peersFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    suspend fun savePeers(peers: List<Peer>) = withContext(Dispatchers.IO) {
        savePeersBlocking(peers)
    }

    /**
     * 非挂起版本：PeerRegistry 的 HTTP 处理路径（同步上下文）要更新地址，
     * 那里开不出协程。文件只有几十行，直接写比引入边界划算。
     */
    fun savePeersBlocking(peers: List<Peer>) {
        peersFile.parentFile?.mkdirs()
        peersFile.writeText(
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Peer.serializer()), peers),
        )
    }
}
