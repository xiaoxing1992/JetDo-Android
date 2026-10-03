# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Compose related classes
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Ktor classes
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes for serialization
-keep,includedescriptorclasses class com.arkdo.android.data.**$$serializer { *; }
-keepclassmembers class com.arkdo.android.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.arkdo.android.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep WebView JavaScript Interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Bugly 崩溃上报
-keep class com.tencent.bugly.** { *; }
-keepclassmembers class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**
