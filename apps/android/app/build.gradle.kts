plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.qingjian.ime"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.qingjian.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.4-android.1"
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

    // cargo-ndk 把 .so 放到 src/main/jniLibs/<abi>/，这里再显式声明一次免得被 AGP 忽略
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    // dict.qj 随包（3.4 MB），首次启动复制到 filesDir 再交给引擎 mmap
    sourceSets["main"].assets.srcDirs("src/main/assets")
}

dependencies {
    // 只依赖 Android 自带的 org.json 与 framework，不引入 AndroidX，编得最快
}
