package io.github.Calvariaa.kitchennotes.sync

import com.sun.net.httpserver.HttpExchange
import jakarta.servlet.DispatcherType
import jakarta.servlet.ReadListener
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletConnection
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.WriteListener
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import jakarta.servlet.http.HttpUpgradeHandler
import jakarta.servlet.http.Part
import org.eclipse.jgit.http.server.GitServlet
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.resolver.RepositoryResolver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.Principal
import java.util.Collections
import java.util.Enumeration
import java.util.Locale

/**
 * 把 `com.sun.net.httpserver` 的出入口桥接到 **jakarta.servlet** 接口，让 JGit 的 GitServlet 直接可用。
 *
 * 两个必须知道的坑：
 *  1. JGit 7.x 已从 `javax.servlet` 迁到 `jakarta.servlet`（6.1）。按 javax 写会得到
 *     成片 "Unresolved reference 'servlet'"。
 *  2. jakarta.servlet 6.1 的接口比 5.x 多了 `getRequestId / getProtocolRequestId / getServletConnection`，
 *     且 JGit 7.x 的 `RepositoryResolver` 是 `open(C, String)` 两参数形式。
 *
 * 为什么不引 Jetty：Jetty 12 ee11 会给 APK 增加 6MB+ dex 和一堆 JDK 服务端假设，
 * 而我们只需要三个端点（GET /info/refs、POST /git-upload-pack、POST /git-receive-pack）。
 */
internal class MiniGitHandler(
    private val repository: Repository,
    private val host: GitServerHost,
) {
    private val servlet = GitServlet()

    /** 主机身份，由 GitServerHost 启动时注入；post-receive 拉取时用作署名兜底 */
    @Volatile
    var currentIdentity: io.github.Calvariaa.kitchennotes.data.UserIdentity =
        io.github.Calvariaa.kitchennotes.data.UserIdentity(name = "dish", email = "dish@localhost")

    init {
        servlet.init(object : ServletConfig {
            override fun getServletName(): String = "git"
            override fun getServletContext(): ServletContext? = null
            override fun getInitParameter(name: String?): String? = null
            override fun getInitParameterNames(): Enumeration<String> = Collections.emptyEnumeration()
        })
        servlet.setRepositoryResolver(SingleRepoResolver(repository))
        servlet.setReceivePackFactory { _, repo ->
            org.eclipse.jgit.transport.ReceivePack(repo).apply {
                setPostReceiveHook { _, _ ->
                    // 成员推完，主机工作区要跟上；后台线程做，别阻塞 HTTP 响应
                    Thread {
                        runCatching {
                            kotlinx.coroutines.runBlocking { host.pullBareIntoWorkspace(currentIdentity) }
                        }
                    }.also { it.isDaemon = true }.start()
                }
            }
        }
    }

    fun handle(exchange: HttpExchange) {
        val req = ServletRequestAdapter(exchange)
        val resp = ServletResponseAdapter(exchange)
        try {
            servlet.service(req, resp)
        } catch (t: Throwable) {
            runCatching {
                if (!resp.isCommitted) {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.close()
                }
            }
        }
    }
}

/** 单仓库解析：路径已由 HttpServer 的 context 限定，任何请求都给同一个仓库 */
private class SingleRepoResolver(
    private val repository: Repository,
) : RepositoryResolver<HttpServletRequest> {
    override fun open(req: HttpServletRequest, name: String?): Repository = repository
}

// ---------------------------------------------------------------- 请求适配

private class ServletRequestAdapter(
    private val ex: HttpExchange,
) : HttpServletRequest {

    private val uri: String = ex.requestURI.toString()

    override fun getMethod(): String = ex.requestMethod
    override fun getRequestURI(): String = uri
    override fun getRequestURL(): StringBuffer = StringBuffer(uri)
    override fun getQueryString(): String? = uri.substringAfter('?', "").ifBlank { null }
    override fun getProtocol(): String = ex.protocol

    /** GitServlet 挂在 /grocery.git，pathInfo 必须是 /info/refs 这种相对路径 */
    override fun getServletPath(): String = "/grocery.git"
    override fun getPathInfo(): String {
        val path = uri.substringBefore('?')
        return path.removePrefix("/grocery.git").ifBlank { "/" }
    }

    override fun getContextPath(): String = ""
    override fun getRemoteAddr(): String = ex.remoteAddress?.address?.hostAddress ?: "0.0.0.0"
    override fun getRemoteHost(): String = remoteAddr
    override fun getRemotePort(): Int = ex.remoteAddress?.port ?: 0
    override fun getRemoteUser(): String? = null
    override fun getLocalAddr(): String = ex.localAddress?.address?.hostAddress ?: "127.0.0.1"
    override fun getLocalName(): String = "localhost"
    override fun getLocalPort(): Int = ex.localAddress?.port ?: 0
    override fun getServerName(): String = "localhost"
    override fun getServerPort(): Int = ex.localAddress?.port ?: 0
    override fun getScheme(): String = "http"
    override fun isSecure(): Boolean = false

    override fun getHeader(name: String?): String? = name?.let { ex.requestHeaders.getFirst(it) }
    override fun getHeaders(name: String?): Enumeration<String> =
        Collections.enumeration(name?.let { ex.requestHeaders[it] } ?: emptyList())

    override fun getHeaderNames(): Enumeration<String> =
        Collections.enumeration(ex.requestHeaders.keys.toList())

    override fun getIntHeader(name: String?): Int = getHeader(name)?.toIntOrNull() ?: -1
    override fun getDateHeader(name: String?): Long = -1L

    override fun getContentType(): String? = getHeader("Content-Type")
    override fun getCharacterEncoding(): String =
        getContentType()?.substringAfter("charset=", "UTF-8")?.trim() ?: "UTF-8"

    override fun setCharacterEncoding(env: String?) = Unit
    override fun getContentLength(): Int = getHeader("Content-Length")?.toIntOrNull() ?: -1
    override fun getContentLengthLong(): Long = getContentLength().toLong()

    override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
        private val delegate = ex.requestBody
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
        override fun isFinished(): Boolean = runCatching { delegate.available() == 0 }.getOrDefault(true)
        override fun isReady(): Boolean = true
        override fun setReadListener(listener: ReadListener?) = Unit
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(getInputStream(), getCharacterEncoding()))

    // ---- 容器特性本项目不使用，给安全默认值 ----
    override fun getParameter(name: String?): String? = null
    override fun getParameterValues(name: String?): Array<String>? = null
    override fun getParameterNames(): Enumeration<String> = Collections.emptyEnumeration()
    override fun getParameterMap(): MutableMap<String, Array<String>> = mutableMapOf()

    override fun getAttribute(name: String?): Any? = null
    override fun getAttributeNames(): Enumeration<String> = Collections.emptyEnumeration()
    override fun setAttribute(name: String?, o: Any?) = Unit
    override fun removeAttribute(name: String?) = Unit

    override fun getCookies(): Array<Cookie> = emptyArray()
    override fun getSession(create: Boolean): HttpSession? = null
    override fun getSession(): HttpSession? = null
    override fun changeSessionId(): String = ""
    override fun getRequestedSessionId(): String? = null
    override fun isRequestedSessionIdValid(): Boolean = false
    override fun isRequestedSessionIdFromCookie(): Boolean = false
    override fun isRequestedSessionIdFromURL(): Boolean = false

    override fun authenticate(response: HttpServletResponse?): Boolean = false
    override fun login(username: String?, password: String?) = Unit
    override fun logout() = Unit
    override fun getAuthType(): String? = null
    override fun getUserPrincipal(): Principal? = null
    override fun isUserInRole(role: String?): Boolean = false
    override fun getServletContext(): ServletContext? = null
    override fun getLocale(): Locale = Locale.getDefault()
    override fun getLocales(): Enumeration<Locale> =
        Collections.enumeration(listOf(Locale.getDefault()))

    override fun getRequestDispatcher(path: String?): RequestDispatcher? = null
    override fun getPathTranslated(): String? = null

    override fun getAsyncContext(): jakarta.servlet.AsyncContext? = null
    override fun isAsyncStarted(): Boolean = false
    override fun isAsyncSupported(): Boolean = false
    override fun startAsync(): jakarta.servlet.AsyncContext =
        throw IllegalStateException("async not supported")

    override fun startAsync(
        request: ServletRequest?,
        response: ServletResponse?,
    ): jakarta.servlet.AsyncContext = throw IllegalStateException("async not supported")

    override fun getDispatcherType(): DispatcherType = DispatcherType.REQUEST
    override fun getParts(): MutableCollection<Part> = mutableListOf()
    override fun getPart(name: String?): Part? = null

    // jakarta 6.1 新增的三个方法
    override fun getRequestId(): String = uri.hashCode().toString()
    override fun getProtocolRequestId(): String = ""
    override fun getServletConnection(): ServletConnection = object : ServletConnection {
        override fun getConnectionId(): String = "dish-${ex.hashCode()}"
        override fun getProtocol(): String = "http"
        override fun getProtocolConnectionId(): String = ""
        override fun isSecure(): Boolean = false
    }

    override fun <T : HttpUpgradeHandler?> upgrade(handlerClass: Class<T>?): T =
        throw UnsupportedOperationException("upgrade not supported")

    override fun newPushBuilder(): jakarta.servlet.http.PushBuilder? = null
    override fun getTrailerFields(): MutableMap<String, String> = mutableMapOf()
    override fun isTrailerFieldsReady(): Boolean = true

    override fun getHttpServletMapping(): jakarta.servlet.http.HttpServletMapping =
        object : jakarta.servlet.http.HttpServletMapping {
            override fun getMatchValue(): String = "grocery.git"
            override fun getPattern(): String = "/grocery.git/*"
            override fun getServletName(): String = "git"
            override fun getMappingMatch(): jakarta.servlet.http.MappingMatch =
                jakarta.servlet.http.MappingMatch.PATH
        }
}

// ---------------------------------------------------------------- 响应适配

/**
 * 响应适配的关键点：git smart HTTP 的响应体长度事先不可知，必须用 chunked。
 * `sendResponseHeaders(status, 0)` 就是 `com.sun.net.httpserver` 的 chunked 开关。
 * 因此不做 body 缓冲，第一次拿 OutputStream 时才把 header 刷出去。
 */
private class ServletResponseAdapter(
    private val ex: HttpExchange,
) : HttpServletResponse {

    private var status: Int = 200
    private var streaming = false
    private var out: Delegate? = null

    override fun isCommitted(): Boolean = streaming
    override fun getStatus(): Int = status

    override fun setStatus(sc: Int) {
        status = sc
    }

    override fun sendError(sc: Int) = sendError(sc, "")
    override fun sendError(sc: Int, msg: String?) {
        status = sc
        runCatching {
            ex.sendResponseHeaders(sc, -1)
            ex.close()
        }
        streaming = true
    }

    override fun sendRedirect(location: String?) {
        ex.responseHeaders.add("Location", location ?: "")
        sendError(302)
    }

    override fun sendRedirect(location: String?, sc: Int, clearBuffer: Boolean) = sendRedirect(location)

    override fun setHeader(name: String?, value: String?) {
        if (name != null) ex.responseHeaders.set(name, value ?: "")
    }

    override fun addHeader(name: String?, value: String?) {
        if (name != null) ex.responseHeaders.add(name, value ?: "")
    }

    override fun setContentType(type: String?) {
        if (type != null) ex.responseHeaders.set("Content-Type", type)
    }

    override fun getContentType(): String? = ex.responseHeaders.getFirst("Content-Type")
    override fun setCharacterEncoding(charset: String?) = Unit
    override fun getCharacterEncoding(): String = "UTF-8"
    override fun setContentLength(len: Int) = Unit
    override fun setContentLengthLong(len: Long) = Unit
    override fun setBufferSize(size: Int) = Unit
    override fun getBufferSize(): Int = 0
    override fun flushBuffer() { startStreaming() }
    override fun reset() = Unit
    override fun resetBuffer() = Unit

    override fun setDateHeader(name: String?, date: Long) {
        if (name != null) ex.responseHeaders.set(name, date.toString())
    }

    override fun addDateHeader(name: String?, date: Long) {
        if (name != null) ex.responseHeaders.add(name, date.toString())
    }

    override fun setIntHeader(name: String?, value: Int) {
        if (name != null) ex.responseHeaders.set(name, value.toString())
    }

    override fun addIntHeader(name: String?, value: Int) {
        if (name != null) ex.responseHeaders.add(name, value.toString())
    }

    override fun addCookie(cookie: Cookie?) {
        val c = cookie ?: return
        ex.responseHeaders.add("Set-Cookie", "${c.name}=${c.value ?: ""}")
    }

    override fun containsHeader(name: String?): Boolean =
        name != null && ex.responseHeaders.containsKey(name)

    override fun getHeader(name: String?): String? = name?.let { ex.responseHeaders.getFirst(it) }

    override fun getHeaders(name: String?): Collection<String> =
        name?.let { ex.responseHeaders[it] } ?: emptyList()

    override fun getHeaderNames(): Collection<String> = ex.responseHeaders.keys.toList()

    override fun encodeURL(url: String?): String = url ?: ""
    override fun encodeRedirectURL(url: String?): String = url ?: ""

    override fun setLocale(locale: Locale?) = Unit
    override fun getLocale(): Locale = Locale.getDefault()

    override fun getOutputStream(): ServletOutputStream {
        startStreaming()
        val real = out!!
        return object : ServletOutputStream() {
            override fun write(b: Int) = real.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = real.write(b, off, len)
            override fun flush() = real.flush()
            override fun close() = real.close()
            override fun isReady(): Boolean = true
            override fun setWriteListener(listener: WriteListener?) = Unit
        }
    }

    override fun getWriter(): PrintWriter =
        PrintWriter(java.io.OutputStreamWriter(getOutputStream(), Charsets.UTF_8), true)

    private fun startStreaming() {
        if (streaming) return
        streaming = true
        runCatching {
            // 0 == chunked：git 协议要求流式响应
            ex.sendResponseHeaders(status, 0)
        }
        out = Delegate(ex.responseBody)
    }

    private class Delegate(private val delegate: java.io.OutputStream) {
        fun write(b: Int) = delegate.write(b)
        fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        fun flush() = delegate.flush()
        fun close() {
            runCatching { delegate.close() }
        }
    }
}
