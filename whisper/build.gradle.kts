plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pl.jassi.whisper"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
