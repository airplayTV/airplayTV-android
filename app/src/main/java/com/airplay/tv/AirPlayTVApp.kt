package com.airplay.tv

import android.app.Application
import com.airplay.tv.app.AppContainer

class AirPlayTVApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
