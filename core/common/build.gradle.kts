plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.arkdo.android.core.common"
    compileSdk = 37

    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
