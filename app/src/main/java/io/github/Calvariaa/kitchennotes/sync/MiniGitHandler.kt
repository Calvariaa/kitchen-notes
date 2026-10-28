package io.github.Calvariaa.kitchennotes.sync

import android.util.Log
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import org.eclipse.jgit.transport.PacketLineOut
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser
import org.eclipse.jgit.transport.UploadPack
import kotlinx.coroutines.sync.withLock

/**
 * 直接实现 git smart HTTP 的三个端点，不做 Servlet 适配。
 *
 * **为什么不复用 JGit 的 `GitServlet`**：它是给真实 Servlet 容器写的，两个硬障碍：
 *  - `GitFilter.init()` 会**无条件**用 `FileResolver(base-path)` 覆盖外部
 *    `setRepositoryResolver()` 设的解析器；
 *  - glue 层拿**整个** `getPathInfo()` 当仓库名，又拿同一个值做 `endsWith` 匹配，
 *    「名字只是 `node.git`」和「路径以 `/info/refs` 结尾」不可能同时满足。
 * 直接用 `UploadPack` / `ReceivePack` 才是 git 协议的真实入口，servlet 只是包装层。
 *
 * 端点（git 客户端协商的固定路径）：
 *  - `GET  /node.git/info/refs?service=git-upload-pack`   拉取广告
 *  - `GET  /node.git/info/refs?service=git-receive-pack`  推送广告
 *  - `POST /node.git/git-upload-pack`                     fetch 数据传输
 *  - `POST /node.git/git-receive-pack`                    push 数据传输
 *
 * 响应体是 pkt-line 流，长度事先不可知 → 必须 chunked（见 [HttpCall.streamHeaders]）。
 *
 * **P2P 语义**：这里读写的仓库是本机的裸仓库 `node.git`（[MeshRepo]）。
 * 对端 push 进来后立刻合并回工作区 —— 因为是全网格，任何节点都可能既是
 * 提供方又是接收方，没有「主机才做 pull」的特权。
 */
internal class MiniGitHandler(
    private val meshRepo: MeshRepo,
    private val server: MeshServer,
) {
    /** 节点身份，post-receive 拉取时用作署名兜底 */
    @Volatile
    var currentIdentity: UserIdentity =
        UserIdentity(name = "dish", email = "dish@localhost")

    /** 对端 push 后、工作区已合并完成的回调（通知 UI 刷新） */
    @Volatile
    /**
     * 工作区被回灌之后回调。参数是这次回灌产生的冲突清单 ——
     * 对端 push 触发的仲裁同样可能覆盖掉别人的改动，UI 必须有机会告诉用户。
     */
    var onWorkspacePulled: ((ConflictReport) -> Unit)? = null

    val prefix: String = "/node.git"

    fun handle(call: HttpCall) {
        val sub = call.path.removePrefix(prefix)
        when {
            sub == "/info/refs" -> advertise(call)
            sub == "/git-upload-pack" && call.method == "POST" -> uploadPack(call)
            sub == "/git-receive-pack" && call.method == "POST" -> receivePack(call)
            else -> call.respondText(404, "no such git endpoint: $sub")
        }
    }

    // ------------------------------------------------------------ 广告

    private fun advertise(call: HttpCall) {
        val service = call.query?.split('&')
            ?.firstOrNull { it.startsWith("service=") }
            ?.substringAfter('=')
        if (service == null) {
            call.respondText(400, "service parameter required")
            return
        }
        val kind = when (service) {
            "git-upload-pack", "git-receive-pack" -> service
            else -> {
                call.respondText(403, "service not enabled: $service")
                return
            }
        }

        val out = call.streamHeaders(200, "application/x-$kind-advertisement") ?: return
        out.use { body ->
            val repo = runCatching { meshRepo.openBare() }.getOrNull()
            if (repo == null) {
                call.respondText(500, "repository unavailable")
                return
            }
            repo.use {
                // smart HTTP 广告以 "# service=..." 开头，空行之后才是真正的 ref 列表
                val pkt = PacketLineOut(body)
                pkt.writeString("# service=$kind\n")
                pkt.end()

                if (kind == "git-upload-pack") {
                    UploadPack(repo).also { it.setBiDirectionalPipe(false) }
                        .sendAdvertisedRefs(PacketLineOutRefAdvertiser(pkt))
                } else {
                    ReceivePack(repo).also { it.setBiDirectionalPipe(false) }
                        .sendAdvertisedRefs(PacketLineOutRefAdvertiser(pkt))
                }
                body.flush()
            }
        }
    }

    // ------------------------------------------------------------ fetch

    private fun uploadPack(call: HttpCall) {
        val out = call.streamHeaders(200, "application/x-git-upload-pack-result") ?: return
        out.use { body ->
            runCatching {
                meshRepo.openBare().use { repo ->
                    val up = UploadPack(repo)
                    up.setBiDirectionalPipe(false)
                    // 三个参数是 in / out / msgOut。
                    // msgOut 必须传 null：JGit 在 uploadWithExceptionPropagation 里会把 out
                    // 包成 rawOut，客户端协商 side-band 时它自己造 SideBandOutputStream
                    // （进度走 band 2、pack 走 band 1）。外部塞流进去会被当原始通道直写，
                    // 进度文本没有 pkt 分帧 → 客户端报 "bad line length character: Tota"。
                    up.upload(call.body.inputStream(), body, null)
                }
            }.onFailure { Log.w(TAG, "upload-pack failed", it) }
            runCatching { body.flush() }
        }
    }

    // ------------------------------------------------------------ push

    private fun receivePack(call: HttpCall) {
        val out = call.streamHeaders(200, "application/x-git-receive-pack-result") ?: return
        out.use { body ->
            runCatching {
                meshRepo.openBare().use { repo ->
                    val rp = ReceivePack(repo)
                    rp.setBiDirectionalPipe(false)
                    rp.setPostReceiveHook { _, _ ->
                        // 对端推完，本机工作区要跟上。后台线程做，别阻塞 HTTP 响应。
                        // 必须持 GitLock：此刻 UI 的 commitAndPush / 定时同步很可能
                        // 正在同一个仓库上 add/commit，不串行化就是 index.lock 抢锁，
                        // 异常又被 runCatching 吞掉 → 该合并的没合并（坑 38）。
                        Thread {
                            // 回灌时也可能撞车（对端推来的和自己刚改的是同一条记录）。
                            // 这条路径以前把仲裁结果整个丢掉，于是「对端 push 触发的那次冲突」
                            // 对用户完全不可见 —— 明明有人的改动被盖了，界面上什么都不说。
                            var report = ConflictReport.NONE
                            kotlinx.coroutines.runBlocking {
                                GitLock.mutex.withLock {
                                    report = runCatching {
                                        meshRepo.pullBareIntoWorkspace(currentIdentity)
                                    }.getOrDefault(ConflictReport.NONE)
                                }
                            }
                            onWorkspacePulled?.invoke(report)
                        }.also { it.isDaemon = true }.start()
                    }
                    rp.receive(call.body.inputStream(), body, null)
                }
            }.onFailure { Log.w(TAG, "receive-pack failed", it) }
            runCatching { body.flush() }
        }
    }

    private companion object {
        const val TAG = "MiniGitHandler"
    }
}
