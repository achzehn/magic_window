# Xposed API 由框架提供，仅编译期存在
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# Hook 入口与被 hook 侧反射调用的类必须保留
-keep class com.github.lsposed.magicwindow.hook.** { *; }
-keep class com.github.lsposed.magicwindow.ModuleStatus { *; }

-keepattributes *Annotation*
