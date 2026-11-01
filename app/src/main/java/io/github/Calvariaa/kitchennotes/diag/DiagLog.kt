package io.github.Calvariaa.kitchennotes.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内的运行日志 —— 设置页「终端日志」页面的数据源。
 *
 * 为什么不用 logcat：用户不会接电脑，release 包也拿不到 logcat。
 * 「两台手机同步还是有问题、复现过程未知」这类问题，唯一能拿到的证据
 * 就是**用户自己把日志复制出来发给我**。所以日志必须：
 *  1. 落盘（重启后还在），而不只是内存环形缓冲；
 *  2. 覆盖 git 与 p2p 同步的每一步（谁连谁、fetch 到什么、merge 结果、push 结果）；
 *  3. 用户能自己敲一行字进去打标记（「刚点了同步」），把操作和日志对上。
 *
 * 设计取舍：
 *  - **写入必须在调用方线程同步完成**。同步链路里的失败经常紧跟一次
 *    `runCatching` 吞异常（坑 38），异步写的日志在崩溃/杀进程时就丢了，
 *    而丢的恰好是出事前最后几行。文件本身很小，追加几十字节的开销远小于一次 git 操作。
 *  - **绝不抛异常**。日志失败的代价远小于把一条同步链路带崩（坑 15）。
 *  - 按行裁剪而不是按字符：一行日志就是一条事实，截半行比多留几行更难读。
 */
object DiagLog {

    private const val FILE_NAME = "diag.log"

    /** 用户输入的标记存在这里，和自动日志分开：清屏不该把用户自己写的东西也删掉 */
    private const val NOTES_NAME = "diag-notes.log"

    /** 最多留这么多行。按行裁剪后约 300–500 KB，手机上看也翻得动 */
    private const val MAX_LINES = 6000

    /** 单行最多这么长：JGit 的异常栈塞进一行会把整页日志挤爆，保留头部定位问题足够 */
    private const val MAX_LINE_CHARS = 900

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** @Volatile 不行：`mutableStatements` 不保证线程安全，加锁更省心且开销可忽略 */
    private val stampLock = Any()

    private fun now(): String = synchronized(stampLock) { stamp.format(Date()) }

    fun file(ctx: Context): File = File(androidPath(ctx), FILE_NAME)

    fun notesFile(ctx: Context): File = File(androidPath(ctx), NOTES_NAME)

    private fun androidPath(ctx: Context) =
        File(ctx.filesDir, "store").apply { mkdirs() }

    // ------------------------------------------------------------ 写

    fun d(tag: String, msg: String) = write('D', tag, msg, null)

    fun i(tag: String, msg: String) = write('I', tag, msg, null)

    fun w(tag: String, msg: String, error: Throwable? = null) = write('W', tag, msg, error)

    fun e(tag: String, msg: String, error: Throwable? = null) = write('E', tag, msg, error)

    /**
     * 用户自己敲进来的标记。走同一条文件但带 `USER` 级别，
     * 和自动日志一眼就能分开 —— 用户照着它说「我就在这一步出问题的」。
     */
    fun user(ctx: Context, text: String) = write('U', "USER", text, null)

    private fun write(level: Char, tag: String, msg: String, error: Throwable?) {
        val line = buildString {
            append(now())
            append(' ').append(level)
            append(' ').append(tag)
            append(' ').append(msg)
            if (error != null) {
                append(" :: ").append(error.javaClass.simpleName)
                error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        }.let { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…" else it }

        // 双写：logcat 仍然有效（有人用 adb 时更顺手），文件是给用户复制的
        when (level) {
            'E' -> Log.e(tag, msg, error)
            'W' -> Log.w(tag, msg, error)
            else -> Log.i(tag, msg)
        }

        val ctx = CtxRef.current ?: return
        runCatching { append(ctx, line) }
    }

    private fun append(ctx: Context, line: String) {
        val f = file(ctx)
        // 先看大小再决定是否整体重写：每次追加都读全文会让日志本身变成性能问题
        val tooBig = runCatching { f.length() > MAX_LINES * 120L }.getOrDefault(false)
        if (!tooBig) {
            f.appendText(line + "\n")
            return
        }
        val kept = runCatching { f.readText().lines() }.getOrDefault(emptyList())
        val merged = (kept.takeLast(MAX_LINES * 3 / 4) + line).joinToString("\n")
        f.writeText(merged + "\n")
    }

    // ------------------------------------------------------------ 读 / 清

    fun read(ctx: Context, maxLines: Int = Long.MAX_VALUE.toInt()): String =
        runCatching {
            file(ctx).takeIf { it.exists() }?.readText().orEmpty()
                .lines()
                .filter { it.isNotEmpty() }
                .let { if (it.size > maxLines) it.takeLast(maxLines) else it }
                .joinToString("\n")
        }.getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    /** 用户标记：整行读回，UI 直接展示 */
    fun readNotes(ctx: Context): List<String> =
        runCatching {
            notesFile(ctx).takeIf { it.exists() }?.readText().orEmpty()
                .lines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    fun appendNote(ctx: Context, note: String) {
        val clean = note.trim().replace("\n", " ")
        if (clean.isEmpty()) return
        runCatching {
            val f = notesFile(ctx)
            f.appendText("${now()} $clean\n")
        }
        // 用户标记也进总日志，导出的文本里能看出时间顺序
        write('U', "NOTE", clean, null)
    }

    fun clearNotes(ctx: Context) {
        runCatching { notesFile(ctx).delete() }
    }

    /**
     * Application 上下文。日志调用点遍布 sync/ 与 data/，
     * 每个都多传一个 Context 会污染接口；而这里唯一需要 Context 的理由是落盘。
     * 由 [install] 在 Application.onCreate 里注入一次即可。
     */
    private object CtxRef {
        @Volatile
        var current: Context? = null
    }

    fun install(ctx: Context) {
        CtxRef.current = ctx.applicationContext
    }
}
