# Keep hook classes
-keep class com.github.lsposed.magicwindow.hook.** { *; }

# libxposed 现代 API：类由框架在运行时注入，仅编译期可见
-dontwarn io.github.libxposed.annotation.**

# Keep annotations
-keepattributes *Annotation*
