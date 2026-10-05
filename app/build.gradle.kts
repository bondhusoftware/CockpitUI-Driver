plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bondhu.cockpitdriver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bondhu.cockpitdriver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}

kotlin {
    jvmToolchain(17)
}
