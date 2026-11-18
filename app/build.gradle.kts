plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.phoneshare"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.example.phoneshare"
        minSdk = 24
        targetSdk = 34
        // 版本号随 GitHub 编译序号递增：v1.0.N，versionCode = N
        val runNumber = (project.findProperty("runNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
