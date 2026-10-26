package io.github.Calvariaa.kitchennotes

import android.app.Application
import io.github.Calvariaa.kitchennotes.ui.DishViewModel

class KitchenNotesApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: KitchenNotesApp
            private set
    }
}
