plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.arkdo.android.core.datastore"
    compileSdk = 37

    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
}
