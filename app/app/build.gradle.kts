import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
 id("com.android.application")
}

android {
 namespace = "com.zcoderemote"
 compileSdk = 36

 defaultConfig {
 applicationId = "com.zcoderemote"
 minSdk = 26
 targetSdk = 34
 versionCode = 2
 versionName = "1.1"
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
}

// AGP 9 内置 Kotlin（不单独应用 kotlin-android 插件）
kotlin {
 compilerOptions {
 jvmTarget.set(JvmTarget.JVM_17)
 }
}

dependencies {
 // core 1.17+ 才有 NotificationCompat.setProgress 样式与 setRequestPromotedOngoing（ 灵动岛标准通道）
 implementation("androidx.core:core-ktx:1.17.0")
 implementation("androidx.appcompat:appcompat:1.6.1")
 // 文档创建时注入 JS（ 帧拦截：赶在页面建 WebSocket 前覆写）
 implementation("androidx.webkit:webkit:1.9.0")
 // 扫码：纯 zxing 实现，不依赖 Google Play 服务
 implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
