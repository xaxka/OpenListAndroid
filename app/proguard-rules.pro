# Go 内核（gomobile AAR）：反射回调与 JNI 入口全部保留
-keep class alistlib.** { *; }
-dontwarn alistlib.**

# EasyTier JNI 绑定：native 方法按包名/类名符号解析（Java_com_easytier_jni_EasyTierJNI_*），
# 包名与类名不可混淆/重命名；native 方法保留以维持 JNI 注册。
-keep class com.easytier.jni.** { *; }
-keepclassmembers class com.easytier.jni.** {
    native <methods>;
}
-dontwarn com.easytier.jni.**

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
