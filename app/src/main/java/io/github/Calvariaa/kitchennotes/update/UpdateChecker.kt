package io.github.Calvariaa.kitchennotes.update

import android.content.Context
import io.github.Calvariaa.kitchennotes.data.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 一个可安装的新版本。
 *
 * 只保留「装它需要的东西」：版本号、APK 直链、可选的 sha256 文件直链。
 * [sha256Url] 来自我们自己的 CI（workflow 会把 `<apk>.sha256` 一起传到 Release），
 * 拿不到就退化成「只校验 zip 完整性」，不因为缺校验文件就拒绝安装。
 */
data class UpdateInfo(
    /** GitHub 的 draft 标记：草稿版绝不能拿来安装 */
    val isDraft: Boolean = false,
    /** 去掉前缀 v 的 tag，例如 1.2.0 */
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String,
    val apkFileName: String,
    val sha256Url: String?,
    val sizeBytes: Long,
    val publishedAt: String,
)

/**
 * 去 GitHub Releases 找最新版。
 *
 * 为什么用 Releases API 而不是自建更新服务器：这个项目的发版流程已经在 CI 里
 * （tag → Release → APK + sha256），让 App 直接读同一个来源，就不需要第二套发布通道。
 *
 * 两个容易踩的点：
 *  - 仓库还一个 Release 都没有时，`/releases/latest` 返回 **404**，这是正常状态，
 *    不是错误，要当「已是最新」处理。
 *  - GitHub 对匿名请求限速 60 次/小时（按 IP）。启动时静默检查一次即可，不做轮询。
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * @param includePrerelease 是否把 draft=false 的预发布版也算进候选。
     *   `/releases/latest` 天然排除预发布，所以开启时改走列表接口自己挑。
     * @return 有新版本时返回 [UpdateInfo]；已是最新或仓库还没有任何 Release 时返回 null。
     * @throws UpdateError 网络/解析失败
     */
    suspend fun fetchLatest(
        repo: String,
        currentVersion: String,
        includePrerelease: Boolean = false,
    ): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val url = if (includePrerelease) {
                "https://api.github.com/repos/$repo/releases?per_page=10"
            } else {
                "https://api.github.com/repos/$repo/releases/latest"
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            val body = try {
                client.newCall(request).execute().use { resp ->
                    when {
                        // 没有任何 Release —— 正常状态，当作「没有新版」
                        resp.code == 404 -> return@withContext null
                        !resp.isSuccessful -> throw UpdateError.Http(resp.code, resp.message)
                        else -> resp.body?.string()
                    }
                }
            } catch (e: IOException) {
                throw UpdateError.Network(e.message)
            } ?: throw UpdateError.Parse("empty body")

            val info = try {
                // 列表模式下 body 是数组，挑第一个带 APK 的；单条模式直接解析对象
                val candidates = if (includePrerelease) parseList(body) else listOfNotNull(parse(body))
                candidates.firstOrNull { !it.isDraft }
            } catch (e: UpdateError) {
                throw e
            } catch (t: Throwable) {
                throw UpdateError.Parse(t.message)
            } ?: return@withContext null

            if (isNewer(info.versionName, currentVersion)) info else null
        }

    /** `/releases?per_page=N` 的数组响应 → 逐个解析，任何一条坏掉不影响其它 */
    private fun parseList(body: String): List<UpdateInfo> {
        val arr = Json.parseToJsonElement(body).jsonArray
        return arr.mapNotNull { element ->
            runCatching { parse(element.toString()) }.getOrNull()
        }.filterNot { it.isDraft }
    }

    private fun parse(body: String): UpdateInfo? {
        val root = Json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val version = tag.removePrefix("v").trim()
        if (version.isBlank()) return null

        val assets = root["assets"] as? kotlinx.serialization.json.JsonArray ?: return null
        val apk = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
            asset["name"]?.jsonPrimitive?.contentOrNullSafe()?.endsWith(".apk", ignoreCase = true) == true
        } ?: return null

        val apkName = apk["name"]!!.jsonPrimitive.contentOrNullSafe() ?: return null
        val apkUrl = apk["browser_download_url"]!!.jsonPrimitive.contentOrNullSafe() ?: return null
        val size = apk["size"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L

        // 校验文件是我们自己的 workflow 传的，命名固定为 <apk>.sha256
        val shaAsset = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
            asset["name"]?.jsonPrimitive?.contentOrNullSafe() == "$apkName.sha256"
        }

        return UpdateInfo(
            isDraft = root["draft"]?.jsonPrimitive?.contentOrNullSafe() == "true",
            versionName = version,
            tagName = tag,
            notes = root["body"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            apkUrl = apkUrl,
            apkFileName = apkName,
            sha256Url = shaAsset?.get("browser_download_url")
                ?.jsonPrimitive?.contentOrNullSafe(),
            sizeBytes = size,
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        )
    }

    /**
     * 版本比较：只看开头的数字段（1.2.3 / 1.2.3-dev.4+gabc 都按 1.2.3 处理）。
     *
     * 规则（够用且不会推错方向）：
     *  1. 数字段更大 → 更新。
     *  2. 数字段相同，本地是 `-dev`/`-rc` 这类预发布、远端是正式版 → 更新。
     *  3. 其他情况都不算更新 —— 宁可漏推一次，也不要让用户被降级。
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val (remoteNums, remotePre) = splitVersion(remote)
        val (localNums, localPre) = splitVersion(local)

        for (i in 0 until maxOf(remoteNums.size, localNums.size)) {
            val r = remoteNums.getOrNull(i) ?: 0
            val l = localNums.getOrNull(i) ?: 0
            if (r != l) return r > l
        }
        // 数字完全一样：只有「本地是预发布、远端不是」才算更新
        return localPre.isNotEmpty() && remotePre.isEmpty()
    }

    private fun splitVersion(v: String): Pair<List<Int>, String> {
        val cleaned = v.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.takeWhile { it.isDigit() || it == '.' }
        val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
        val pre = cleaned.removePrefix(core).trimStart('-', '+', '.')
        return nums to pre
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * APK 下载器。
 *
 * 下载到 `files/updates/` 而不是 cache：cache 可能被系统在门口清理掉，
 * 用户点「安装」时文件已经没了会很困惑。装完由调用方删除。
 *
 * 进度按已写字节回调，UI 拿它画进度条 —— 家庭 WiFi 拉 20MB 要十几秒，
 * 没有进度用户会以为卡死。
 */
class ApkDownloader(
    private val client: OkHttpClient = UpdateChecker.defaultClient(),
) {

    suspend fun download(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = Paths.updatesDir(ctx)
        // 清掉上次没装成的残留，避免占着存储
        dir.listFiles()?.forEach { it.delete() }

        val target = File(dir, info.apkFileName)

        val request = Request.Builder().url(info.apkUrl).build()
        val total: Long
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw UpdateError.Http(resp.code, resp.message)
                val body = resp.body ?: throw UpdateError.Parse("empty body")
                total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes.takeIf { it > 0 } ?: 0L

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            // 每 256KB 报一次，别让 UI 每读 64KB 就重组一次
                            if (done - lastReport >= 256 * 1024) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                        output.flush()
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw UpdateError.Network(e.message)
        }

        verify(ctx, info, target)
        target
    }

    /** 有 sha256 就严格比对；没有就只做一个最小完整性检查（APK 是 zip，头两字节必须是 PK） */
    private suspend fun verify(ctx: Context, info: UpdateInfo, apk: File) {
        val expected = info.sha256Url?.let { url ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) null
                        else resp.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                    }
                }.getOrNull()
            }
        }

        if (!expected.isNullOrBlank()) {
            val actual = sha256(apk)
            if (!actual.equals(expected, ignoreCase = true)) {
                apk.delete()
                throw UpdateError.Checksum(expected, actual)
            }
            return
        }

        val ok = apk.length() > 0 && runCatching {
            apk.inputStream().use { s ->
                val head = ByteArray(2)
                s.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
        if (!ok) {
            apk.delete()
            throw UpdateError.Parse("downloaded file is not an APK")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 更新流程的错误。同样只存可翻译的分类 + 技术细节，不存现成句子。 */
open class UpdateError(
    val kind: Kind,
    val detail: String? = null,
) : Exception(detail ?: kind.name) {

    enum class Kind { NETWORK, HTTP, PARSE, CHECKSUM, INSTALL }

    class Network(detail: String?) : UpdateError(Kind.NETWORK, detail)
    class Http(val code: Int, val reason: String?) : UpdateError(Kind.HTTP, "HTTP $code $reason")
    class Parse(detail: String?) : UpdateError(Kind.PARSE, detail)
    class Checksum(val expected: String, val actual: String) :
        UpdateError(Kind.CHECKSUM, "expected=$expected actual=$actual")

    class Install(detail: String?) : UpdateError(Kind.INSTALL, detail)
}
