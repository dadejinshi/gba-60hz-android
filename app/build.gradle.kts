plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gba60hz.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gba60hz.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.6.0"

        // 只保留 arm64-v8a / armeabi-v7a：本 App 不内置 wasm 原生库，
        // 但显式声明可避免打包 x86 空目录，并给未来可选的原生内核留位。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // WebView + JS 桥接，混淆需保留 @JavascriptInterface，先用 false 稳妥
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // assets 里的 GBA-60Hz.html 较大（~350KB），关闭 PNG 压缩对 html 无影响，
    // 这里保留默认即可；如后续放入 wasm/.gba 二进制，用 aaptOptions 排除压缩。
    packaging {
        resources {
            excludes += setOf("/META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // WebViewAssetLoader：把 assets 映射到 https://appassets.androidplatform.net/，
    // 保持安全 origin，让 IndexedDB / WebGL / WebAudio 像在 http 下一样工作（关键）。
    implementation("androidx.webkit:webkit:1.12.1")
}
