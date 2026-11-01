package io.github.Calvariaa.kitchennotes.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import io.github.Calvariaa.kitchennotes.AppGraph
import io.github.Calvariaa.kitchennotes.BuildConfig
import io.github.Calvariaa.kitchennotes.sync.MeshCrypto
import io.github.Calvariaa.kitchennotes.data.Paths
import org.eclipse.jgit.api.Git
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「一次性体检报告」：把排查同步问题需要的环境事实一次全拿到。
 *
 * 日志回答「刚才发生了什么」，这一份回答「现在处于什么状态」——
 * 两台手机都同步报错时，先看两份报告的 nodeId / 名册 / 分支 / 端口是否对得上，
 * 能立刻分出是「对端没起来」还是「数据真的不一致」。
 *
 * 全部字段都是只读收集，且每段单独 [runCatching]：任何一项取不到只写一行「不可用」，
 * 不能让整份报告因为一个反射失败而空掉。
 */
object DebugSnapshot {

    private const val MAX_PEERS = 12

    private const val MAX_COMMITS = 12

    fun build(ctx: Context, graph: AppGraph): String {
        val settings = runCatching { graph.settings.get() }.getOrNull()
        val identity = runCatching { graph.settings.identity() }.getOrNull()
        val group = runCatching { graph.settings.group() }.getOrNull()
        val peers = runCatching { graph.peerRegistry.all() }.getOrDefault(emptyList())

        val sb = StringBuilder()
        sb.line("===== Kitchen Notes 诊断 =====")
        sb.line("生成时间: ${stamp()}")
        sb.line("")

        // ---------------- 版本 / 设备 ----------------
        sb.section("版本与设备")
        sb.kv("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.kv("package", BuildConfig.APPLICATION_ID)
        sb.kv("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.kv("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        sb.kv("abi", Build.SUPPORTED_ABIS.joinToString(","))
        sb.kv("locale", java.util.Locale.getDefault().toLanguageTag())
        sb.kv("时区", java.util.TimeZone.getDefault().id)
        sb.kv("语言设置", settings?.language ?: "?")
        sb.line("")

        // ---------------- 网络 ----------------
        sb.section("网络")
        sb.kv("监听端口", settings?.serverPort?.toString() ?: "?")
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            sb.kv("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?.toString() ?: "?")
            sb.kv("有线", caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?.toString() ?: "?")
            sb.kv("已验证联网", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: "?")
            val lp: LinkProperties? = cm?.getLinkProperties(net)
            sb.kv("本机地址", lp?.linkAddresses?.joinToString(",") { it.address.hostAddress ?: "?" } ?: "?")
            sb.kv("路由", lp?.routes?.joinToString(",") { it.toString() } ?: "?")
            // 同一局域网的两台设备必须落在同一个 /24 上；不同网段就是「连不上」的头号原因
            lp?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.let {
                sb.kv("网段 /24", "${it.address.hostAddress?.substringBeforeLast('.')}.0/24")
            }
        }.onFailure { sb.kv("网络", "不可用: ${it.javaClass.simpleName}") }
        // 服务端视角的本机地址，和系统网卡列表对不上就说明服务没起来
        runCatching { sb.kv("节点监听地址", graph.meshServer.addresses().joinToString(",").ifBlank { "<无>" }) }
        sb.line("")

        // ---------------- 身份 / 家庭 ----------------
        sb.section("身份与家庭组")
        sb.kv("用户名", identity?.name ?: "<无身份>")
        sb.kv("邮箱", identity?.email ?: "?")
        sb.kv(
            "nodeId",
            identity?.let { MeshCrypto.nodeId(it.sshPublicKey) }?.ifBlank { "<公钥为空>" } ?: "?",
        )
        sb.kv("有私钥", identity?.sshPrivateKeyPem?.isNotBlank()?.toString() ?: "?")
        sb.kv("家庭组", group?.name ?: "<未加入>")
        sb.kv("家庭密钥", if (group?.secret.isNullOrBlank()) "<空>" else "已配置(${group?.secret?.length} 字符)")
        sb.kv("加入码", group?.takeIf { it.secret.isNotBlank() }?.let { MeshCrypto.inviteCode(it.secret) } ?: "<无>")
        sb.kv("加入时间", group?.joinedAt?.takeIf { it > 0 }?.let { stamp(it) } ?: "?")
        sb.line("")

        // ---------------- 名册 ----------------
        sb.section("对端名册 (${peers.size})")
        peers.take(MAX_PEERS).forEachIndexed { idx, p ->
            // 公钥只印指纹，不印全文：日志是要发给别人的，正文没有排查价值
            sb.kv(
                "#${idx + 1}",
                "name=${p.name.ifBlank { "?" }} addr=${p.address.ifBlank { "?" }} " +
                    "seen=${p.lastSeenAt.takeIf { it > 0 }?.let { stamp(it) } ?: "?"} " +
                    "synced=${p.synced} node=${p.nodeId.take(24)}…",
            )
        }
        if (peers.isEmpty()) sb.line("  <空名册：这台设备还没见过任何对端>")
        sb.line("")

        // ---------------- git ----------------
        sb.section("git 仓库")
        val repoDir = runCatching { Paths.repo(ctx) }.getOrNull()
        val bareDir = runCatching { Paths.bare(ctx) }.getOrNull()
        if (repoDir != null) sb.gitState("工作区 ${repoDir.absolutePath}", repoDir)
        if (bareDir != null) sb.gitState("裸仓库 ${bareDir.absolutePath}", bareDir)
        runCatching { sb.filesSummary(ctx) }
        runCatching { sb.conflictBackups(ctx) }
        sb.line("")

        // ---------------- 同步状态 ----------------
        sb.section("同步")
        sb.kv("自动同步", settings?.autoSyncMinutes?.let { if (it <= 0) "关闭" else "每 $it 分钟" } ?: "?")
        sb.kv("启动同步", settings?.syncOnLaunch?.toString() ?: "?")
        sb.kv("编辑后推送", settings?.pushAfterEdit?.toString() ?: "?")
        sb.line("")
        return sb.toString()
    }

    private fun StringBuilder.section(title: String) {
        line("--- $title ---")
    }

    private fun StringBuilder.kv(key: String, value: String) {
        line("  $key: $value")
    }

    private fun StringBuilder.line(text: String) {
        append(text).append('\n')
    }

    private fun stamp(at: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))

    /** 工作区 / 裸仓库各打一份：分支、HEAD、领先落后、最近提交 */
    private fun StringBuilder.gitState(label: String, dir: File) {
        line("[$label]")
        if (!dir.exists()) {
            line("  <仓库不存在>")
            return
        }
        runCatching {
            // 用 Git.open 而不是手工 FileRepositoryBuilder：裸仓库与工作区的路径规则
            // （.git 还是目录本身）交给 JGit 判断，省得这里再分一次 bare 与非 bare
            Git.open(dir).use { git ->
                val repo = git.repository
                line("  bare: ${repo.isBare}")
                line("  分支: ${repo.branch ?: "<detached/unborn>"}")
                val head = runCatching { repo.resolve("HEAD") }.getOrNull()
                line("  HEAD: ${head?.name()?.take(12) ?: "<空仓库>"}")
                // HEAD 的提交历史和最近几条。同步出问题时，「两台设备的提交集合是否一致」
                // 比任何日志都直接 —— 少了哪道菜的提交在这里一眼看见
                val commits = runCatching { git.log().setMaxCount(MAX_COMMITS).call().toList() }
                    .getOrDefault(emptyList())
                if (commits.isEmpty()) {
                    line("  <没有提交>")
                } else {
                    line("  最近提交 (最多 $MAX_COMMITS 条):")
                    commits.forEach { c ->
                        val msg = c.shortMessage.orEmpty().replace('\n', ' ').take(70)
                        line("    ${c.name.take(8)} ${stamp(c.commitTime * 1000L)} ${c.authorIdent.name}: $msg")
                    }
                }
                runCatching { repo.refDatabase.getRefs() }.getOrDefault(emptyList()).forEach { r ->
                    line("  ref ${r.name} -> ${r.objectId.name.take(12)}")
                }
            }
        }.onFailure {
            line("  <无法打开: ${it.javaClass.simpleName}: ${it.message}>")
        }
    }

    /** 各目录文件数：数据「去哪了」最先看这个 */
    private fun StringBuilder.filesSummary(ctx: Context) {
        val root = Paths.root(ctx)
        line("目录统计:")
        listOf(
            "repo/dishes" to File(Paths.repo(ctx), "dishes"),
            "repo/menus" to File(Paths.repo(ctx), "menus"),
            "repo/images" to File(Paths.repo(ctx), "images"),
            "conflicts" to File(root, "conflicts"),
            "store" to root,
        ).forEach { (name, dir) ->
            val n = runCatching {
                if (!dir.exists()) -1 else dir.walkTopDown().count { it.isFile }
            }.getOrDefault(-1)
            line("  $name: ${if (n < 0) "<不存在>" else "$n 个文件"}")
        }
    }

    /**
     * 冲突备份。有这个目录基本就说明「某次同步真的覆盖掉了东西」——
     * 用户说「菜谱丢了」时，这里是他能找回数据的唯一线索。
     */
    private fun StringBuilder.conflictBackups(ctx: Context) {
        val dir = File(Paths.root(ctx), "conflicts")
        if (!dir.exists()) return
        val files = dir.listFiles()?.sortedByDescending { it.name } ?: return
        if (files.isEmpty()) return
        line("冲突备份 (${files.size}):")
        files.take(MAX_PEERS).forEach { line("  ${it.name} (${it.length()} B)") }
    }
}
