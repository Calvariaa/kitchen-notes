package io.github.Calvariaa.kitchennotes.sync

import io.github.Calvariaa.kitchennotes.R

/**
 * 同步结果的结构化描述。
 *
 * 为什么不让同步层直接拼一句人话：同步层拿不到「当前界面语言」，
 * 而且它可能在用户切语言之前就跑完了，拼好的字符串不会跟着变。
 * 所以这里只输出**发生了什么**（[SyncPart] + 计数），由 UI 层用当前语言渲染。
 */
data class SyncResult(
    val pushedCommits: Int = 0,
    val pulledCommits: Int = 0,
    val conflictsResolved: Int = 0,
    /** 发生了哪些事，按发生顺序；UI 逐条翻译并用逗号接起来 */
    val parts: List<SyncPart> = emptyList(),
    /** 只有 [SyncPart.ADOPTED] 用得到，家庭组名字 */
    val groupName: String = "",
)

/** 同步过程中的一个可翻译事实 */
enum class SyncPart(val resId: Int) {
    /** 拉取了 N 个提交 */
    PULLED(R.string.sync_part_pulled),

    /** 合并了 N 条冲突（保留较新） */
    CONFLICTS(R.string.sync_part_conflicts),

    /** 已推送本地改动 */
    PUSHED(R.string.sync_part_pushed),

    /** 已是最新 */
    UP_TO_DATE(R.string.sync_part_up_to_date),

    /** 已是家庭组主机，数据就在本机 */
    HOST_LOCAL(R.string.sync_already_host),

    /** 已提交本地改动（不联网） */
    COMMITTED_LOCAL(R.string.sync_committed_local),

    /** 已接入某个家庭组，但主机那边还没有菜谱 */
    ADOPTED_EMPTY(R.string.sync_part_adopted_empty),
}

/**
 * 同步失败。
 *
 * 同样不存现成句子：存一个可翻译的 [Kind] 加一段原文（JGit/网络给的消息，
 * 本身没法翻译，当作技术细节显示）。
 */
class SyncError(
    val kind: Kind,
    /** 底层技术细节，可为空 */
    val detail: String? = null,
) : Exception(detail ?: kind.name) {

    enum class Kind(val resId: Int) {
        FETCH_FAILED(R.string.sync_err_fetch),
        PUSH_FAILED(R.string.sync_err_push),
        NO_GROUP(R.string.sync_err_no_group),
        NO_IDENTITY(R.string.sync_no_identity),
        HOST_NOT_RUNNING(R.string.sync_err_host_not_running),
        UNREACHABLE(R.string.sync_err_unreachable),
        HOST_UNREACHABLE(R.string.sync_err_host_unreachable),
        JOIN_REJECTED(R.string.sync_err_join_rejected),
        BAD_RESPONSE(R.string.sync_err_bad_response),
        TOKEN_REJECTED(R.string.sync_err_token),
        MISSING_FIELDS(R.string.sync_err_missing_fields),
        PORT_UNAVAILABLE(R.string.sync_err_port),
        ;

        /** 是否需要附带技术细节 */
        val wantsDetail: Boolean
            get() = this in setOf(FETCH_FAILED, PUSH_FAILED, UNREACHABLE, HOST_UNREACHABLE, PORT_UNAVAILABLE)
    }
}
