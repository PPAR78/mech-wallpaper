plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kimdaepyo.mechwall"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kimdaepyo.mechwall"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// 타사 라이브러리 없음 — Android 프레임워크 + Kotlin 표준 라이브러리만 사용
dependencies { }
