package com.airplay.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.airplay.tv.data.api.AirPlayApi
import com.airplay.tv.data.db.AppDatabase
import com.airplay.tv.data.preferences.AppPreferences
import com.airplay.tv.data.repository.VideoRepository
import com.airplay.tv.ui.navigation.AppNavigation
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(this)
        val db = AppDatabase.getInstance(this)
        val api = createApi()
        val repo = VideoRepository(api)

        setContent {
            AppNavigation(prefs = prefs, db = db, repo = repo)
        }
    }
}

fun createApi(): AirPlayApi {
    val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("X-Client", "airplayTV-web")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(logging)
        .build()
    return Retrofit.Builder()
        .baseUrl("https://airplay-api.artools.cc")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AirPlayApi::class.java)
}
