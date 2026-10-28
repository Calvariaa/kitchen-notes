package io.github.Calvariaa.kitchennotes.sync

import io.github.Calvariaa.kitchennotes.R

/**
 * 同步结果的结构化描述。
 *
 * 为什么不让同步层直接拼一句人话：同步层拿不到「当前界面语言」，
 * 而且它可能在用户切语言之前就跑完了，拼好的字符串不会跟着变。
 * 所以这里只输出**发生了什么**（[SyncPart] + 计数），由 UI 用当前语言渲染。
 */
data class SyncResult(
    val pushedCommits: Int = 0,
    val pulledCommits: Int = 0,
    val conflictsResolved: Int = 0,
    /** 发生过的事，按发生顺序；UI 逐条翻译并用逗号接起来 */
    val parts: List<SyncPart> = emptyList(),
    /** 只有 [SyncPart.ADOPTED] 用得到，家庭组名字 */
    val groupName: String = "",
    /** 这一轮成功连上了几个对端 */
    val peersReached: Int = 0,
    /**
     * 冲突的明细。UI 用它渲染「这次撞了哪些、谁被盖了」的弹窗。
     *
     * 为什么要单独带一份结构：`conflictsResolved` 只是个数字，
     * 说不出「哪道菜的哪个字段被盖了」，用户没法据此去核对或找回。
     */
    val conflictReport: ConflictReport = ConflictReport.NONE,
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

    /** 已提交本地改动（没有对端在线，或还没加入家庭） */
    COMMITTED_LOCAL(R.string.sync_committed_local),

    /** 已接入某个家庭组，但对端还没有菜谱 */
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
    /**
     * 第二段细节。`sync_err_port` 这类文案有两个占位符（端口号、原因），
     * 而 [detail] 只能放一个字段。分开存比在字符串里用 `|` 拼再拆要干净 ——
     * 拆的时候端口号里也可能出现分隔符，早晚出问题。
     */
    val hint: String? = null,
) : Exception(detail ?: kind.name) {

    enum class Kind(val resId: Int) {
        FETCH_FAILED(R.string.sync_err_fetch),
        PUSH_FAILED(R.string.sync_err_push),
        NO_GROUP(R.string.sync_err_no_group),
        NO_IDENTITY(R.string.sync_no_identity),
        UNREACHABLE(R.string.sync_err_unreachable),
        JOIN_REJECTED(R.string.sync_err_join_rejected),
        BAD_RESPONSE(R.string.sync_err_bad_response),
        TOKEN_REJECTED(R.string.sync_err_token),
        MISSING_FIELDS(R.string.sync_err_missing_fields),
        PORT_UNAVAILABLE(R.string.sync_err_port),
        REPO_UNAVAILABLE(R.string.sync_err_repo),
        ;

        /** 是否需要附带技术细节 */
        val wantsDetail: Boolean
            get() = this in setOf(FETCH_FAILED, PUSH_FAILED, UNREACHABLE, PORT_UNAVAILABLE, REPO_UNAVAILABLE)
    }
}
