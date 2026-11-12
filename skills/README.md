# SingVerse 项目技能

本目录保存项目使用的技能说明及配套资源，适用于网页管理端、Flutter 手机端和 Android 设备验证。按任务阅读相应 `SKILL.md`，不需要一次加载全部技能。

## 技能索引

| 技能 | 适用任务 | 使用边界 |
|---|---|---|
| [unslop](unslop/SKILL.md) | 改善已有界面的间距、颜色、字体、文案、图标和视觉层次 | 以现有产品功能及组件体系为依据；用于 Flutter / Compose 时将设计原则转成原生实现 |
| [huashu-design](huashu-design/SKILL.md) | 明确要求的 HTML 设计稿、视觉方向探索、演示和设计评审 | 不是生产 Web App 或后端实现方案；不因普通功能修改自动生成原型 |
| [dart-use-pattern-matching](dart-use-pattern-matching/SKILL.md) | Dart 复杂条件、JSON 分支、sealed 类型及解构重构 | 简单判断不必改成模式匹配，不为套用技能扩大重构范围 |
| [flutter-add-widget-test](flutter-add-widget-test/SKILL.md) | 为具体组件添加渲染、点击、滚动及输入行为测试 | 验证可观察行为，沿用工程现有测试结构和依赖 |
| [flutter-code-review](flutter-code-review/SKILL.md) | 审查明确范围内的 Flutter / Dart 改动与回归风险 | 独立审查默认报告证据和问题；修复需属于用户授权范围 |
| [android-emulator-browser](android-emulator-browser/SKILL.md) | 通过 adb 连接模拟器或真机，镜像屏幕、操作界面、检查焦点和日志 | 需要实际设备连接；网页预览不能代替原生应用验收 |

## 在本项目中使用

- 先遵循根 [AGENTS.md](../AGENTS.md)，再按任务读取技能及必要的 `references/`、`examples/` 或 `scripts/`。用户明确要求和当前授权范围优先于技能默认工作流。
- 网页使用 Vue / TypeScript / Element Plus；手机使用 Flutter；TV 使用 Kotlin / Compose TV。设计原则可参考，React、Tailwind 或 HTML 示例不能直接替代生产端实现。
- 修改 UI 前阅读生产源码及现有主题、组件和状态边界；保留点歌、播放、录音、授权等业务语义。TV 检查遥控焦点，手机检查文字缩放及触控目标。
- 原型与历史截图已清理，不恢复旧原型目录。只有任务明确需要设计探索时才生成新设计资料；按用户指定位置保存，否则生成的截图、录屏和报告放被忽略的临时目录或模块构建报告目录。
- 不把测试生成的数据、录音、截图或运行日志提交到技能目录；技能本身附带的示例和测试夹具属于技能资源，不等同于历史验收产物。
- 技能目录是仓库资料，不保证被运行环境自动发现。需要时直接读取索引中的 `SKILL.md`，不能仅因目录存在就声称已安装或已执行技能。

## 维护约定

- 新增、移除或调整技能时同步本索引，链接只指向实际存在的文件；当前没有 `ui-ux-pro-max` 技能，不保留其旧入口。
- 保留第三方技能的作者信息、许可证和来源。`unslop` 作者为 Damir Sokolovsky（@heydamir），采用 CC BY 4.0；`huashu-design` 的作者及来源见其 `SKILL.md`，其他技能按各自声明处理。
- 项目差异优先写在本索引或仓库协作指南中，不为适配一次任务批量改写第三方技能内容。
