# ==============================================================================
# ClipPort Android ProGuard / R8 保护规则
# ==============================================================================

# 1. 基础属性与注解保留
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# 2. LibXposed 模块入口与元数据
-keep class com.clipport.app.xposed.XposedEntry { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class * extends io.github.libxposed.api.XposedInterface { *; }
-keepclassmembers class * extends io.github.libxposed.api.XposedInterface { *; }

# 3. 自研轻量 Protobuf 协议类与消息字段
-keep class com.clipport.app.protocol.** { *; }
-keepclassmembers class com.clipport.app.protocol.** { *; }

# 4. ZXing 纯 Java 扫码核心库
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# 5. CameraX 硬件相机与预览分析类
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.**

# 6. Kotlin 标准库与协程
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
