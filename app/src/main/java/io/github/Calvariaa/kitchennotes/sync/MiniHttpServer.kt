package io.github.Calvariaa.kitchennotes.sync

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP/1.1 服务器，只为 git smart HTTP 与三个 JSON 端点存在。
 *
 * **为什么不用 `com.sun.net.httpserver.HttpServer`**：那是 JDK 内部类，Android 上
 * 编译期能解析、**运行时根本没有**。一执行 `HttpServer.create()` 就
 * `NoClassDefFoundError`，表现为「点创建家庭直接闪退」—— 而且这个错在
 * `viewModelScope.launch` 里没人接，整个进程跟着走。
 *
 * 为什么也不用 NanoHTTPD / Ktor：只需要 3 个 git 端点 + 4 个 JSON 端点，
 * 手写 200 行比引一个框架更可控，还能精确控制 chunked 与 keep-alive 的边界。
 *
 * 实现取舍（够用即止，不是通用 web server）：
 *  - 每个连接一个线程，`Connection: close`，不做 keep-alive（git 客户端能接受）；
 *  - 请求体按 Content-Length 或 chunked 读全量到内存 —— git 单次 pack 通常几 MB，
 *    家庭局域网场景可接受；超大仓库才需要改成流式；
 *  - 响应一律 chunked，因为 git 协议长度事先不可知。
 */
class MiniHttpServer(private val port: Int) {

    /** 路由：前缀 → 处理器。按最长前缀匹配。 */
    private val routes = LinkedHashMap<String, (HttpCall) -> Unit>()

    private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var executor = Executors.newFixedThreadPool(6)

    fun route(prefix: String, handler: (HttpCall) -> Unit): MiniHttpServer {
        routes[prefix] = handler
        return this
    }

    val isRunning: Boolean get() = running.get()

    /** @throws IOException 端口被占用等，调用方负责转成 UI 文案 */
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        socket = ss
        running.set(true)
        if (executor.isShutdown) executor = Executors.newFixedThreadPool(6)
        Thread({ acceptLoop(ss) }, "mini-http-$port").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        runCatching { executor.shutdownNow() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept failed", e)
                break
            }
            runCatching {
                executor.execute {
                    runCatching { serve(client) }
                        .onFailure { Log.w(TAG, "request failed", it) }
                    runCatching { client.close() }
                }
            }.onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty().split(' ')
        if (requestLine.size < 2) {
            writePlain(output, 400, "bad request")
            return
        }
        val method = requestLine[0].uppercase()
        val target = requestLine[1]

        val headers = LinkedHashMap<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            headers.getOrPut(name.lowercase()) { mutableListOf() }.add(value)
        }

        val body = readBody(input, headers)

        val call = HttpCall(
            method = method,
            target = target,
            path = target.substringBefore('?'),
            query = target.substringAfter('?', "").ifBlank { null },
            headers = headers,
            body = body,
            remote = client.inetAddress?.hostAddress ?: "0.0.0.0",
            localPort = port,
        )

        val handler = routes.entries
            .filter { call.path == it.key || call.path.startsWith(it.key + "/") }
            .maxByOrNull { it.key.length }
            ?.value

        if (handler == null) {
            writePlain(output, 404, "not found")
            return
        }
        call.output = output
        handler(call)
        if (!call.committed) writePlain(output, 500, "handler produced no response")
        runCatching { output.flush() }
    }

    // ------------------------------------------------------------ 读请求

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        while (true) {
            val b = input.read()
            if (b < 0) return buf.toString("ISO-8859-1").ifBlank { null }
            buf.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                return buf.toString("ISO-8859-1").trimEnd()
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
            if (buf.size() > MAX_HEAD) throw IOException("header too large")
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, List<String>>): ByteArray {
        val chunked = headers["transfer-encoding"]?.firstOrNull()
            ?.contains("chunked", ignoreCase = true) == true
        if (chunked) return readChunked(input)
        val len = headers["content-length"]?.firstOrNull()?.toLongOrNull() ?: return ByteArray(0)
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len.toInt())
        var off = 0
        while (off < out.size) {
            val n = input.read(out, off, out.size - off)
            if (n < 0) break
            off += n
        }
        return if (off == out.size) out else out.copyOf(off)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) {
                // 吃掉 trailer 直到空行
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            val chunk = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = input.read(chunk, off, size - off)
                if (n < 0) break
                off += n
            }
            out.write(chunk, 0, off)
            readLine(input) // chunk 末尾的 CRLF
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            buf.write(b)
            if (buf.size() > MAX_HEAD) throw IOException("line too large")
        }
    }

    // ------------------------------------------------------------ 响应小工具

    private fun writePlain(output: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(bytes)
            output.flush()
        }
    }

    companion object {
        private const val TAG = "MiniHttpServer"
        private const val MAX_HEAD = 64 * 1024

        internal fun reason(code: Int): String = when (code) {
            200 -> "OK"
            204 -> "No Content"
            302 -> "Found"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Status"
        }
    }
}

/**
 * 一次 HTTP 请求 + 响应。字段是解析后的快照，处理器只读；写响应走 [output]。
 *
 * 刻意做成普通类而不是复用 `com.sun.net.httpserver.HttpExchange`：
 * 后者在 Android 上不存在（见 [MiniHttpServer] 类注释）。
 */
class HttpCall(
    val method: String,
    val target: String,
    val path: String,
    val query: String?,
    private val headers: Map<String, List<String>>,
    val body: ByteArray,
    val remote: String,
    val localPort: Int,
) {
    var output: OutputStream? = null
    var committed: Boolean = false
        private set

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    fun headers(name: String): List<String> = headers[name.lowercase()] ?: emptyList()

    fun bodyText(): String = body.toString(Charsets.UTF_8)

    /** 定长响应：状态行 + 头 + 体，一次写完 */
    fun respond(code: Int, contentType: String?, bytes: ByteArray) {
        val out = output ?: return
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(MiniHttpServer.reason(code)).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        committed = true
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    fun respondJson(code: Int, json: String) =
        respond(code, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

    fun respondText(code: Int, text: String) =
        respond(code, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    /**
     * chunked 流式响应。git 协议的长度事先不可知，必须分块；
     * 用固定长度会截断（把 -1 当长度写死是经典错误）。
     */
    fun streamHeaders(code: Int, contentType: String?): ChunkedOutput? {
        val out = output ?: return null
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(MiniHttpServer.reason(code)).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n\r\n")
        }
        committed = true
        return runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            ChunkedOutput(out)
        }.getOrNull()
    }
}

/** chunked 写入器：每次 write 包一层十六进制长度行 */
class ChunkedOutput(private val out: OutputStream) : OutputStream() {

    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed || len <= 0) return
        val sizeLine = "${Integer.toHexString(len)}\r\n".toByteArray(Charsets.ISO_8859_1)
        out.write(sizeLine)
        out.write(b, off, len)
        out.write(CRLF)
        out.flush()
    }

    override fun flush() = runCatching { out.flush() }.let { }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            out.write(LAST_CHUNK)
            out.flush()
        }
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        private val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
