-keepattributes Signature
-keepattributes *Annotation*
-dontwarn javax.annotation.**

# Tink 仅把该类型作为编译期注解使用，运行时没有反射或调用依赖。
-dontwarn com.google.errorprone.annotations.Immutable

# Vosk 通过 JNA 在运行时注册 native 方法。Release 的 R8 必须保留两侧完整类型和成员，
# 否则 Debug 可正常加载而压缩后的正式包会在模型准备阶段触发 NoClassDefFoundError。
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
# JNA 同一 Android 产物包含不会在移动端调用的桌面 AWT 适配分支。
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

-keep class com.tencent.mm.opensdk.** { *; }
-keep class com.tencent.wxop.** { *; }
-keep class com.tencent.mm.sdk.** { *; }
