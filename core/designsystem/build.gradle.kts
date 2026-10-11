plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jetdo.android.core.designsystem"
    compileSdk = 37

    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    // api 而非 implementation：SharedSearchTransition（放大镜共享元素接线盒）的公开签名里
    // 直接出现 SharedTransitionScope / AnimatedVisibilityScope，消费方（feature:home /
    // feature:search）只调 Modifier 扩展，但 app 要用这两个类型构造接线盒。
    api(libs.compose.animation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.backdrop)
    implementation(libs.timber)
    testImplementation(libs.junit)
}
