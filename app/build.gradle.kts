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
        versionCode = 16
        versionName = "1.0.16"
    }

    // v16: স্থায়ী keystore — প্রতিটা বিল্ড একই signature-এ সাইন হবে,
    // তাই নতুন APK পুরনোটার উপর সরাসরি বসবে (আনইন্সটল লাগবে না, সেটআপ মুছবে না)।
    signingConfigs {
        create("persistent") {
            storeFile = file("debug-persistent.keystore")
            storePassword = "cockpitdriver"
            keyAlias = "cockpitdriver"
            keyPassword = "cockpitdriver"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("persistent")
        }
    }
}

kotlin {
    jvmToolchain(17)
}
