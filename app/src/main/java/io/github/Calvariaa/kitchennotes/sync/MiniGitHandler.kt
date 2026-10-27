package io.github.Calvariaa.kitchennotes.sync

import android.util.Log
import io.github.Calvariaa.kitchennotes.data.UserIdentity
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.PacketLineOut
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.RefAdvertiser.PacketLineOutRefAdvertiser
import org.eclipse.jgit.transport.UploadPack

/**
 * 直接实现 git smart HTTP 的三个端点，不做 Servlet 适配。
 *
 * **为什么不复用 JGit 的 `GitServlet`**：它是给真实 Servlet 容器写的，内部 glue 层
 * （MetaFilter / UrlPipeline / RepositoryFilter）假设 `getPathInfo()` 里「仓库名」与
 * 「剩余路径」的切分由容器完成，而且 `GitFilter.init()` 会**无条件**用
 * `FileResolver(base-path)` 覆盖外部通过 `setRepositoryResolver` 挂的解析器。
 * 在自建 HTTP 服务器上凑这些前提，试错成本远高于直接用 `UploadPack` / `ReceivePack`
 * —— 那才是 git 协议的真实入口，servlet 只是它的包装层。
 *
 * 端点（git 客户端协商的固定协议）：
 *  - `GET  /grocery.git/info/refs?service=git-upload-pack`   拉取广告
 *  - `GET  /grocery.git/info/refs?service=git-receive-pack`  推送广告
 *  - `POST /grocery.git/git-upload-pack`                     fetch 数据传输
 *  - `POST /grocery.git/git-receive-pack`                    push 数据传输
 *
 * 响应体是 pkt-line 流，长度事先不可知 → 必须 chunked（见 [HttpCall.streamHeaders]）。
 */
internal class MiniGitHandler(
    private val repository: Repository,
    private val host: GitServerHost,
) {
    /** 主机身份，post-receive 拉取时用作署名兜底 */
    @Volatile
    var currentIdentity: UserIdentity =
        UserIdentity(name = "dish", email = "dish@localhost")

    /** 成员 push 后、工作区已 pull 完成的回调（用于通知 UI 刷新） */
    @Volatile
    var onWorkspacePulled: (() -> Unit)? = null

    /** 仓库名前缀，三个端点都挂在它下面 */
    val prefix: String = "/grocery.git"

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
            "git-upload-pack" -> service
            "git-receive-pack" -> service
            else -> {
                call.respondText(403, "service not enabled: $service")
                return
            }
        }

        val out = call.streamHeaders(200, "application/x-$kind-advertisement") ?: return
        out.use { body ->
            // smart HTTP 广告以 "# service=..." 开头，空行之后才是真正的 ref 列表
            val pkt = PacketLineOut(body)
            pkt.writeString("# service=$kind\n")
            pkt.end()

            if (kind == "git-upload-pack") {
                val up = UploadPack(repository)
                up.setBiDirectionalPipe(false)
                up.sendAdvertisedRefs(PacketLineOutRefAdvertiser(pkt))
            } else {
                val rp = ReceivePack(repository)
                rp.setBiDirectionalPipe(false)
                rp.sendAdvertisedRefs(PacketLineOutRefAdvertiser(pkt))
            }
            body.flush()
        }
    }

    // ------------------------------------------------------------ fetch

    private fun uploadPack(call: HttpCall) {
        val out = call.streamHeaders(200, "application/x-git-upload-pack-result") ?: return
        out.use { body ->
            try {
                val up = UploadPack(repository)
                up.setBiDirectionalPipe(false)
                // 三个参数分别是 in / out / msgOut。
                // msgOut 必须传 null：JGit 在 uploadWithExceptionPropagation 里会把 out
                // 包成 rawOut，客户端协商了 side-band 时它自己造 SideBandOutputStream
                // （进度走 band 2、pack 走 band 1）。外部塞进来的流会被直接当原始通道用，
                // 进度文本没经过 pkt 分帧 → 客户端报
                // "bad line length character: Tota"。
                up.upload(call.body.inputStream(), body, null)
            } catch (t: Throwable) {
                // chunked 已经开始，状态码改不了了，只能记录
                Log.w(TAG, "upload-pack failed", t)
            }
            body.flush()
        }
    }

    // ------------------------------------------------------------ push

    private fun receivePack(call: HttpCall) {
        val out = call.streamHeaders(200, "application/x-git-receive-pack-result") ?: return
        out.use { body ->
            try {
                val rp = ReceivePack(repository)
                rp.setBiDirectionalPipe(false)
                rp.setPostReceiveHook { _, _ ->
                    // 成员推完，主机工作区要跟上；后台线程做，别阻塞 HTTP 响应
                    Thread {
                        runCatching {
                            kotlinx.coroutines.runBlocking {
                                host.pullBareIntoWorkspace(currentIdentity)
                            }
                        }
                        onWorkspacePulled?.invoke()
                    }.also { it.isDaemon = true }.start()
                }
                rp.receive(call.body.inputStream(), body, null)
            } catch (t: Throwable) {
                Log.w(TAG, "receive-pack failed", t)
            }
            body.flush()
        }
    }

    private companion object {
        const val TAG = "MiniGitHandler"
    }
}
