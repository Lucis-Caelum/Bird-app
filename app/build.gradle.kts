plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.luciscaelum.slayautoplayer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.luciscaelum.slayautoplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
