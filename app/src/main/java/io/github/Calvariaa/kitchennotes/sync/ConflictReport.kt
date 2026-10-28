package io.github.Calvariaa.kitchennotes.sync

/**
 * 一次冲突仲裁的结果清单。
 *
 * 为什么要有这个类：同步层能算出「哪些字段撞车了、保留了哪一边」，
 * 但**它拿不到当前界面语言**，也**不该直接拼人话**。
 * 所以这里只留结构化事实（记录 id、字段名、是否丢过东西），
 * 由 UI 用当前语言渲染成弹窗（坑：ViewModel 不能存 String）。
 *
 * 为什么要暴露给 UI：字段级三方合并虽然保住了两边改动，
 * 但**同一字段被两边同时改**时必然有一边落败。旧实现只往
 * `conflicts/merge.log` 写一行日志，用户永远不知道自己的修改被谁盖了（坑 42）。
 *
 * @param kind       这条记录是菜谱还是菜单
 * @param id         记录 id（文件名去掉 .json），用来定位回哪道菜
 * @param fields     两边改到同一个字段、必须二选一的字段名（英文 key，不翻译）
 * @param autoMerged 两边改的是不同字段、已自动保住全部改动的字段名
 * @param backupName 落败版本在 `files/store/conflicts/` 下的文件名，为空表示没备份
 */
data class ConflictEntry(
    val kind: Kind,
    val id: String,
    val fields: List<String>,
    val autoMerged: List<String>,
    val backupName: String?,
) {
    enum class Kind { DISH, MENU }

    /** 有没有真的需要用户知道的损失（只有同字段撞车才算） */
    val lostSomething: Boolean get() = fields.isNotEmpty()
}

/**
 * 一整轮的冲突汇总。
 *
 * [entries] 为空但 [unresolved] > 0 的情况是「标记都解不出来、原文件被保留」——
 * 那种必须让用户知道，因为同步会就此停住，不告诉他就变成「同步好像不动了」。
 */
data class ConflictReport(
    val entries: List<ConflictEntry> = emptyList(),
    val unresolved: Int = 0,
) {
    /** 需要弹窗打扰用户吗：只有真丢了东西、或有解不掉的残留 */
    val needsAttention: Boolean
        get() = unresolved > 0 || entries.any { it.lostSomething }

    val isEmpty: Boolean get() = entries.isEmpty() && unresolved == 0

    val lostCount: Int get() = entries.count { it.lostSomething }

    companion object {
        val NONE = ConflictReport()
    }
}
