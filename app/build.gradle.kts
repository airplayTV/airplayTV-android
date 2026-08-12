plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.airplay.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.airplay.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
dependencies {
    implementation(libs.androidx.core.ktx) 
    implementation(libs.androidx.activity.compose) 
    implementation(libs.androidx.lifecycle.runtime) 
    implementation(libs.androidx.lifecycle.viewmodel) 
    implementation(libs.androidx.lifecycle.runtime.compose) 
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui) 
    implementation(libs.compose.ui.tooling.preview) 
    implementation(libs.compose.foundation) 
    implementation(libs.compose.runtime)
    implementation(libs.compose.material3) 
    debugImplementation(libs.compose.ui.tooling) 
    implementation(libs.tv.foundation)
    implementation(libs.tv.material) 
    implementation(libs.media3.exoplayer) 
    implementation(libs.media3.exoplayer.hls) 
    implementation(libs.media3.ui) 
    implementation(libs.media3.session)
    implementation(libs.retrofit) 
    implementation(libs.retrofit.converter.gson) 
    implementation(libs.okhttp) 
    implementation(libs.okhttp.logging) 
    implementation(libs.coroutines.core) 
    implementation(libs.coroutines.android) 
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
