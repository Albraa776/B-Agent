package com.bagent.app

import android.app.Application

/** Application entry point hosting the shared dependency container. */
class BAgentApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}