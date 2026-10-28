# SingVerse Android TV

包名 `com.mynora.singverse.tv`。独立 Kotlin / Jetpack Compose 工程，使用电视版 Material 3（`androidx.tv:tv-material`），欢迎区域可获取初始焦点并显示边框，没有业务按钮、网络或播放引擎。

版本：AGP 8.9.2、Gradle 8.11.1、Kotlin/Compose Compiler Plugin 2.1.20、Compose BOM 2025.04.01、TV Material 1.0.0、Activity Compose 1.10.1。compile/target SDK 35，min SDK 23，构建使用 JDK 21（字节码目标 17）。

```sh
./gradlew assembleDebug lintDebug
./gradlew installDebug
```

设置 `ANDROID_HOME` 或本地 `local.properties`，安装 SDK 35 / Build Tools 35.0.0。APK 位于 `app/build/outputs/apk/debug/`。Manifest 声明 Leanback 启动入口、电视特性与非必需触摸屏，并提供电视横幅。

安装后在 Android TV 设备或模拟器检查启动和可见焦点；只有一个焦点目标，不实现没有用途的导航控件。APK 编译不代表已完成遥控器设备验证。
