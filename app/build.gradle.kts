import java.util.Properties

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
    namespace = "com.jetdo.android"
    compileSdk = 37

    // 本地配置：签名密钥 + 崩溃上报端点，都从 local.properties 读（该文件不入库）。
    // 缺了任意一项都不挡构建——密钥缺失产出未签名包，上报端点缺失只是关掉崩溃上报，
    // F-Droid 构建机没有这个文件，正好得到「不带任何远程上报」的合规产物。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val keystoreFile = localProps.getProperty("jetdo.release.storeFile")?.let { rootProject.file(it) }
    signingConfigs {
        if (keystoreFile != null && keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = localProps.getProperty("jetdo.release.storePassword")
                keyAlias = localProps.getProperty("jetdo.release.keyAlias")
                keyPassword = localProps.getProperty("jetdo.release.keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.jetdo.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        // 构建时间注入 BuildConfig：日志页顶部展示，用于确认设备上安装的是哪次构建。
        // 取「提交时间」而非 LocalDateTime.now()：后者每次构建都变，会破坏 F-Droid 要求的
        // 可复现构建（同源码两次构建产物应一致）。git 不可用时退回固定值。
        val commitTime: String = run {
            val proc = ProcessBuilder("git", "log", "-1", "--pretty=%cI")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
            proc.waitFor()
            if (proc.exitValue() == 0 && out.isNotEmpty()) out else "unknown"
        }
        buildConfigField("String", "BUILD_TIME", "\"$commitTime\"")

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
            // local.properties 配了密钥才签名；否则产出未签名包（构建不报错，装不上是预期）。
            signingConfig = signingConfigs.findByName("release")
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
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // 扫码登录（出示二维码 + 相机扫码）。zxing core 纯 Java，CameraX 无 GMS 依赖。
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    // 折叠屏姿态：collectFoldingFeaturesAsState() 供给 WideLayoutHost(unfolded = …)
    implementation(libs.compose.material3.adaptive)
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
    // WebView 身份统一(UA Client Hints / X-Requested-With),见 WebSessionManager.applyUserAgent
    implementation(libs.androidx.webkit)
    // 帖内音视频播放(对齐鸿蒙 AVPlayer 播放器):ExoPlayer 引擎 + PlayerView 控制条
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // 上传前转码（视频超站点上限时降到目标体积以内），见 services/upload/VideoTranscoder.kt
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.backdrop)
    implementation(libs.capsule)
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
