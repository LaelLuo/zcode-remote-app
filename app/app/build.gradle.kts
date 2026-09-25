import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
}

// 正式签名：优先环境变量（CI），其次 app/keystore.properties（本地，gitignore）；
// 都缺时 release 构建退回 debug 签名（仅本地快速验证用，不可用于正式发布）
val keystoreProps = Properties()
val keystoreFile = file("keystore.properties")
if (keystoreFile.exists()) {
    FileInputStream(keystoreFile).use { keystoreProps.load(it) }
}
fun ksProp(envKey: String, fileKey: String): String? =
    System.getenv(envKey) ?: keystoreProps.getProperty(fileKey)

android {
    namespace = "com.zcoderemote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zcoderemote"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.8.0"
    }

    signingConfigs {
        if (ksProp("STORE_PASSWORD", "storePassword") != null) {
            create("release") {
                storeFile = file(System.getenv("STORE_FILE") ?: keystoreProps.getProperty("storeFile")!!)
                storePassword = ksProp("STORE_PASSWORD", "storePassword")
                keyAlias = ksProp("KEY_ALIAS", "keyAlias")
                keyPassword = ksProp("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9 内置 Kotlin（不单独应用 kotlin-android 插件）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // core 1.17+ 才有 NotificationCompat.setProgress 样式与 setRequestPromotedOngoing（灵动岛提升请求 API 所需）
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // 文档创建时注入 JS（帧拦截：赶在页面建 WebSocket 前覆写）
    implementation("androidx.webkit:webkit:1.9.0")
    // 扫码：纯 zxing 实现，不依赖 Google Play 服务
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
