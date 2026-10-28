# SingVerse 手机端

Flutter 3.47.5 / Dart 3.13.4，Android application ID `com.mynora.singverse.mobile`。官方 CLI 仅生成 Android 平台，欢迎页使用 Material 3，无业务、网络或本地数据库依赖。

```sh
flutter pub get
dart format --output=none --set-exit-if-changed .
flutter analyze
flutter test
flutter run
flutter build apk --debug
```

需要 Android SDK 36、模板要求的 NDK 与可兼容的 JDK；CI 使用 JDK 21。提交 pubspec.lock，不提交本地 SDK 路径或构建结果。

未来增加 iOS：在装有 Xcode、iOS SDK 和所需 CocoaPods 的 macOS 上，在本目录执行 `flutter create --platforms=ios --org com.mynora.singverse .`，检查 iOS bundle identifier、签名与生成差异，再执行 `flutter build ios --no-codesign` 或连接设备运行。公共 Dart 代码不硬编码 Android 路径；未来新增插件须检查双平台支持。当前没有 iOS 工程和 iOS 验证结果。
