package io.github.Calvariaa.kitchennotes

import android.app.Application
import android.os.Build
import io.github.Calvariaa.kitchennotes.diag.CrashLog
import io.github.Calvariaa.kitchennotes.diag.DiagLog

class KitchenNotesApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // 尽早挂上崩溃记录：Application.onCreate 之前/之中的崩溃也能留证据
        CrashLog.install(this)
        // 诊断日志要先拿到 Context 才能落盘：此后 sync/git 的每条日志都会写进
        // files/store/diag.log，供设置页「终端日志」复制/导出
        DiagLog.install(this)
        DiagLog.i(
            "App",
            "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                " android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}" +
                " device=${Build.MANUFACTURER} ${Build.MODEL}",
        )
        instance = this
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: KitchenNotesApp
            private set
    }
}
