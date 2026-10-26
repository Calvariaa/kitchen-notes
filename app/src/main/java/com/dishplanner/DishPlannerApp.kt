package com.dishplanner

import android.app.Application
import com.dishplanner.ui.DishViewModel

class DishPlannerApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: DishPlannerApp
            private set
    }
}
