package io.github.Calvariaa.kitchennotes.diag

import android.content.Context
import android.os.Build
import io.github.Calvariaa.kitchennotes.data.Paths
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常的落地记录。
 *
 * 为什么需要：release 包在用户设备上闪退时，logcat 拿不到（用户不会接电脑），
 * Play 那种后台崩溃上报又没有。结果是「点了就闪退」这种反馈无法定位。
 * 把栈写到 App 私有目录的 crash.log，再在设置页里给它一个「复制」按钮，
 * 用户随手就能把栈发出来 —— 这比任何间接猜测都可靠。
 *
 * 有意保持极简：只在 install 时链一次，自己绝不抛异常（出问题就吞掉），
 * 并且原样转发给默认处理器，不改变系统原有的崩溃表现。
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"

    /** 最多留这么多条，避免反复崩溃把文件撑大 */
    private const val MAX_ENTRIES = 20

    private const val MAX_CHARS = 200_000

    fun file(ctx: Context): File = File(Paths.root(ctx), FILE_NAME)

    /**
     * 安装处理器。必须在 Application.onCreate 里最早调用 ——
     * 越早，能抓到的启动期崩溃越多。
     */
    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 记录本身绝不能成为新的崩溃源
            runCatching { append(ctx, thread, error) }
            // 交回默认处理器：系统照常结束进程、照常弹「应用已停止」，
            // 我们不接管这个决定，只是顺手留个证据
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun append(ctx: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            appendLine("===== $stamp =====")
            appendLine("thread: ${thread.name}")
            appendLine("app: ${BuildConfigVersion.name} (${BuildConfigVersion.code})")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(sw.toString())
        }

        val f = file(ctx)
        val existing = runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")

        // 新的放前面：用户复制时最先看到的就是最近一次
        var merged = entry + existing
        if (merged.length > MAX_CHARS) merged = merged.take(MAX_CHARS)

        // 按条裁剪
        val marker = "====="
        val parts = merged.split(Regex("(?m)^(?=====)"))
        if (parts.size > MAX_ENTRIES) {
            merged = parts.take(MAX_ENTRIES).joinToString("")
        }

        f.parentFile?.mkdirs()
        f.writeText(merged)
    }

    fun read(ctx: Context): String =
        runCatching { file(ctx).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    /**
     * 崩溃记录里要写清是哪个版本 —— 用 BuildConfig 会被当前包的命名空间绑死，
     * 这里通过反射拿，顺便避免 diag 包反向依赖生成类。
     */
    private object BuildConfigVersion {
        val name: String = runCatching {
            Class.forName("io.github.Calvariaa.kitchennotes.BuildConfig")
                .getField("VERSION_NAME").get(null) as String
        }.getOrDefault("?")
        val code: String = runCatching {
            Class.forName("io.github.Calvariaa.kitchennotes.BuildConfig")
                .getField("VERSION_CODE").get(null).toString()
        }.getOrDefault("?")
    }
}
