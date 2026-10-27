package io.github.Calvariaa.kitchennotes.ui.i18n

import android.content.Context
import io.github.Calvariaa.kitchennotes.R
import io.github.Calvariaa.kitchennotes.ai.AiError
import io.github.Calvariaa.kitchennotes.sync.SyncError
import io.github.Calvariaa.kitchennotes.sync.SyncPart
import io.github.Calvariaa.kitchennotes.sync.SyncResult
import io.github.Calvariaa.kitchennotes.update.UpdateError

/**
 * 一条「待显示的文案」。
 *
 * ViewModel 里会产生大量状态文字（同步结果、报错、生成阶段）。直接存 String 的话
 * 语言一旦在设置页切换，这些已经产出的字符串不会跟着变，会出现
 * 「界面是英文、同步提示还是中文」。
 *
 * 所以 ViewModel 存 [UiText]（资源 id + 参数），真正渲染时才用当前语言的
 * Resources 去取。命令式一点，但比在每个 ViewModel 里注入 Resources
 * 或者给每个状态都记忆语言要干净。
 */
sealed interface UiText {

    /** 纯资源字符串，带格式化参数 */
    data class Res(val id: Int, val args: List<Any> = emptyList()) : UiText

    /**
     * 已经算好的字面量（来自网络/异常消息，无法翻译）。
     * 这类文案保留原文，属于「数据」而不是「界面」。
     */
    data class Raw(val value: String) : UiText

    /**
     * 多条片段用当前语言的分隔符接起来。
     * 「拉取 3 个提交，已是最新」这种东西不该在同步层拼字符串 —— 那里的逗号是写死的中文标点。
     */
    data class Join(val pieces: List<UiText>) : UiText

    fun resolve(resources: android.content.res.Resources): String = when (this) {
        is Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args.toTypedArray())
        is Raw -> value
        is Join -> pieces.joinToString(resources.getString(R.string.list_separator)) { it.resolve(resources) }
    }

    companion object {
        fun of(id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun raw(value: String): UiText = Raw(value)
    }
}

/** 便捷：按设置里的语言标签取字符串 */
fun UiText.resolve(ctx: Context, language: String): String = resolve(ctx.strings(language))

/** 把异常/接口里的自由文本包成可显示的一条 */
fun Throwable.toUiText(fallback: Int): UiText = UiText.Raw(message ?: "").let {
    if ((it as UiText.Raw).value.isBlank()) UiText.of(fallback) else it
}

/**
 * AI 错误 → 可翻译文案。
 * 每种错误带不同数量的参数，所以集中在这里映射一次，UI 侧只管 `.text()`。
 */
fun AiError.toUiText(): UiText = when (this) {
    is AiError.MissingKey -> UiText.of(resId)
    is AiError.Empty -> UiText.of(resId)
    is AiError.Http -> UiText.of(resId, code, detail.orEmpty())
    is AiError.Network -> UiText.of(resId, detail.orEmpty())
    is AiError.Parse -> UiText.of(resId, detail.orEmpty())
}

/**
 * 更新错误 → 可翻译文案。
 *
 * HTTP 用两个占位符（状态码 + 原因），其余只要细节；
 * 校验失败不暴露期望/实际哈希 —— 对用户没意义，只说包坏了要重下。
 */
fun UpdateError.toUiText(): UiText = when (this) {
    is UpdateError.Http -> UiText.of(R.string.update_err_http, code, reason.orEmpty())
    is UpdateError.Checksum -> UiText.of(R.string.update_err_checksum)
    is UpdateError.Install -> UiText.of(R.string.update_err_install, detail.orEmpty())
    is UpdateError.Network -> UiText.of(R.string.update_err_network, detail.orEmpty())
    is UpdateError.Parse -> UiText.of(R.string.update_err_parse, detail.orEmpty())
    else -> when (kind) {
        UpdateError.Kind.NETWORK -> UiText.of(R.string.update_err_network, detail.orEmpty())
        UpdateError.Kind.PARSE -> UiText.of(R.string.update_err_parse, detail.orEmpty())
        UpdateError.Kind.CHECKSUM -> UiText.of(R.string.update_err_checksum)
        UpdateError.Kind.INSTALL -> UiText.of(R.string.update_err_install, detail.orEmpty())
        UpdateError.Kind.HTTP -> UiText.of(R.string.update_err_http, 0, detail.orEmpty())
    }
}

/** 同步错误 → 可翻译文案 */
fun SyncError.toUiText(): UiText = when (kind) {
    SyncError.Kind.PORT_UNAVAILABLE -> {
        // 端口号走 detail，原因走 hint。以前是拼成 "端口|原因" 再按 | 拆开，
        // 但那等于假定原因文本里永远没有 |，而它来自系统/异常消息，不受我们控制。
        UiText.of(kind.resId, detail.orEmpty(), hint.orEmpty())
    }

    else -> if (kind.wantsDetail) {
        UiText.of(kind.resId, detail.orEmpty())
    } else {
        UiText.of(kind.resId)
    }
}

/**
 * 一次同步的多个事实 → 一句话。
 * 用资源里的逗号拼接形式：英文逗号 + 空格，中文全角逗号，各语言自己决定。
 */
fun List<SyncPart>.toUiText(result: SyncResult): UiText {
    if (isEmpty()) return UiText.of(R.string.sync_part_up_to_date)
    // 每条单独渲染成字符串，再用当前语言的分隔符拼接
    val pieces = map { part ->
        if (part == SyncPart.ADOPTED_EMPTY) UiText.of(part.resId, result.groupName)
        else UiText.of(part.resId)
    }
    return UiText.Join(pieces)
}

/** 常用：生成阶段、同步消息的兜底文案集中在这里，避免散落 magic number */
object Msg {
    val generatingPrepare = R.string.stage_prepare
    val generatingCompress = R.string.stage_compress
    val generatingAsk = R.string.stage_ask
    val generatingParse = R.string.stage_parse
    val generatingRefine = R.string.stage_refine
    val generatingFailed = R.string.stage_failed
    val refineFailed = R.string.stage_refine_failed
    val hostStarting = R.string.sync_host_starting
    val hostStarted = R.string.sync_host_started
    val hostFailed = R.string.sync_host_failed
    val hostStopped = R.string.sync_host_stopped
    val joining = R.string.sync_joining
    val joined = R.string.sync_joined
    val joinFailed = R.string.sync_join_failed
    val left = R.string.sync_left
    val syncing = R.string.sync_in_progress
    val syncFailed = R.string.sync_failed
    val keyRegenerated = R.string.sync_key_regenerated
}
