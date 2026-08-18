# Go 内核（gomobile AAR）：反射回调与 JNI 入口全部保留
-keep class alistlib.** { *; }
-dontwarn alistlib.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Exceptions
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.xaxka.openlist.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp（coil-network-okhttp 间接依赖）：可选加密 provider 缺失告警
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager 反射
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
