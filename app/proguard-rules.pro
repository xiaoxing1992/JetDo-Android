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
-keep,includedescriptorclasses class com.jetdo.android.data.**$$serializer { *; }
-keepclassmembers class com.jetdo.android.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.jetdo.android.data.** {
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

# ACRA 崩溃上报
# ACRA 靠反射实例化 plugin builder 与 ReportField，R8 全模式下会被裁掉。
# 官方建议保留 plugin 包与配置构建器；报告字段枚举也要留（序列化时按名字取值）。
-keep class org.acra.** { *; }
-keepclassmembers class org.acra.** { *; }
-dontwarn org.acra.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
