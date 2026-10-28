package com.omarchy.launcher

import android.app.Application

class OmarchyLauncherApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
