package io.github.Calvariaa.kitchennotes

import android.app.Application
import io.github.Calvariaa.kitchennotes.ui.DishViewModel

class KitchenNotesApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // 尽早挂上崩溃记录：Application.onCreate 之前/之中的崩溃也能留证据
        io.github.Calvariaa.kitchennotes.diag.CrashLog.install(this)
        instance = this
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: KitchenNotesApp
            private set
    }
}
