# libxposed 现代 API：类由 LSPosed 框架在运行时提供，仅编译期可见
-dontwarn io.github.libxposed.annotation.**

# 入口类须保留，并在混淆后重写 java_init.list 中的类名
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Hook 入口与被 hook 侧反射调用的类必须保留
-keep class com.github.lsposed.magicwindow.hook.** { *; }

-keepattributes *Annotation*
