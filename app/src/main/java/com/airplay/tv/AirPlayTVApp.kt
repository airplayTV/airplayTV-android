package com.airplay.tv

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import com.airplay.tv.app.AppContainer
import com.airplay.tv.session.SessionViewModelFactory

class AirPlayTVApp : Application() {
    lateinit var appContainer: AppContainer
        private set
    internal var sessionViewModelFactoryOverride: ViewModelProvider.Factory? = null

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }

    internal fun sessionViewModelFactory(): ViewModelProvider.Factory =
        sessionViewModelFactoryOverride ?: SessionViewModelFactory(appContainer)
}
