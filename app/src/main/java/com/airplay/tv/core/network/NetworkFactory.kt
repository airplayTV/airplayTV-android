package com.airplay.tv.core.network

import com.airplay.tv.core.config.AppConfig
import com.airplay.tv.feature.player.VideoApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkFactory {
    private val apiBaseUrl = AppConfig.API_BASE_URL.toHttpUrl().also { baseUrl ->
        require(baseUrl.isHttps) { "API base URL must use HTTPS" }
    }

    fun okHttpClient(debug: Boolean): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .apply {
            if (debug) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    },
                )
            }
        }
        .build()

    fun videoApi(okHttpClient: OkHttpClient): VideoApi = Retrofit.Builder()
        .baseUrl(apiBaseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(VideoApi::class.java)

    private const val PING_INTERVAL_SECONDS = 20L
}
