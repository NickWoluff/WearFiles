# Manifest 中的组件由 AGP 自动保留；AndroidX 使用依赖自带的混淆规则。
# 更新 ViewModel 通过默认工厂反射创建，保留无参构造方法，类名仍可混淆。
-keepclassmembers,allowoptimization,allowobfuscation class com.nickwoluff.wearfiles.UpdateViewModel {
    public <init>();
}

-keep class com.google.wear.input.WearHapticFeedbackConstants { *; }

# Commons Compress 将 Zstandard 与 Brotli 作为可选扩展；应用未打包这两个解码器。
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**

