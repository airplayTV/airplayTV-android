package com.airplay.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class AirPlayTVApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .build()
    }
}
