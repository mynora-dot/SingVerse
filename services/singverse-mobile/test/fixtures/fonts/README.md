# 中文组件测试字体

`UiCapture.ttf` 是 Noto Sans SC 的测试字形子集，仅由测试代码通过 `FontLoader` 加载，不在 `pubspec.yaml` 注册，不随 Android 应用打包。

来源为本机已安装的 Noto Sans SC，字体内记录版本 `Version 0.00 July 30, 2017`、Adobe Systems Incorporated 版权及 SIL Open Font License 1.1。字体来源页记录为 `http://www.google.com/get/noto/`。子集重新命名为 `SingVerse Test CJK` / `UiCapture`，保留原字体版权、作者和授权记录，完整许可证见 `OFL.txt`。

子集包含生成时手机工程 `lib/`、`test/`、`integration_test/` 中的字符及 ASCII，使用 fontTools subset 生成。新增中文视觉用例需要新字形时，可从同许可证的完整 Noto Sans SC 重新提取。测试中统一注册到 `UiCapture`、Material 默认 `Roboto` 及测试引擎 fallback `Ahem`，以便布局和截图共享字形度量；不读取宿主苹果字体或使用绝对字体路径。
