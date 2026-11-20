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
        versionCode = 17
        versionName = "0.17.0"
    }
    signingConfigs {
        create("birdPlay") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("birdPlay")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
}
