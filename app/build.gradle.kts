import java.time.LocalDateTime

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
            )
        )
    }
}

android {
    namespace = "com.arkdo.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.arkdo.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        // 构建时间注入 BuildConfig：日志页顶部展示，用于确认设备上安装的是哪次构建。
        buildConfigField("String", "BUILD_TIME", "\"${LocalDateTime.now()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // 暂不加 .debug 后缀:排查登录人机验证时排除包名变量,与正式包保持同一 applicationId。
            // applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:home"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // Coil (Coil 3)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    // GIF 动图解码(头像/动图 flair):linux.do 的 avatar_template 常是动图内容,
    // 无此扩展则只能解出首帧静态。服务加载器会自动注册 AnimatedImageDecoder/GifDecoder。
    implementation(libs.coil.gif)

    // OkHttp
    // cooked HTML 解析（PostRichContent 的块/行内节点树来源）
    implementation(libs.jsoup)
    // 登录页 X5 内核对比实验(TBS)
    implementation(libs.androidx.browser)
    implementation(libs.tbssdk)
    // WebView 身份统一(UA Client Hints / X-Requested-With),见 WebSessionManager.applyUserAgent
    implementation(libs.androidx.webkit)
    // 标题栏渐进式模糊（对齐鸿蒙 HDS 渐进模糊材质）
    implementation(libs.haze)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Material Components (for Android View system themes)
    implementation(libs.material)

    // Accompanist
    implementation(libs.accompanist.webview)
    implementation(libs.accompanist.systemuicontroller)

    // Timber
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
