# 解析规则库与解析方案

管理入口位于“曲库”，需要管理员登录：

| 菜单 | hash 路由 | 职责 |
|---|---|---|
| 解析方案 | `#/library/filename-parser-profiles` | 日常唯一主路径：检测文件名、点选内置命名格式、组合成员、启停、排序、复制及预览保存 |
| 解析规则 | `#/library/filename-parser` | 高级页：单条自定义模板/正则的新增、编辑、复制、删除，以及引用方案查看；内置格式不需要来这一页 |

Go 托管时路径前加 `/admin/`，Nginx 对外根路径托管时直接使用 hash 路由。两个页面按路由加载。

规则内核只解释文件名，不核实真实歌手身份或验证媒体内容。媒体来源现可绑定整个解析方案，本地及 WebDAV 扫描复用其启用规则顺序并保存结果；方案配置页本身不触发扫描。WebDAV 后台扫描与受鉴权原始媒体播放均已实现，详见[媒体库管理](media-library.md)。

## 规则库：维护定义

每个定义有稳定 ID、名称、类型、表达式或内置引用，不包含启用状态或优先级。新增/编辑在保存时由服务端编译校验，无需先运行组合预览。

- 内置目录提供书名号、尾括号、编号、下划线、正反横线及曲库标签等常用规则；定义只读，可以复制成自定义规则后编辑。解析方案页把它们渲染成可点选的格式卡片，日常不需要在本页启用。
- 规则被多个方案共享引用。修改后，各方案的下一次预览使用新定义；编辑器会提示受影响的方案名称。
- 自定义规则被任一已保存方案引用（包括停用成员）时禁止删除，界面和 409 响应列出方案。先移除所有引用，再删除定义。
- 引用方案可点击跳转，链接携带 `?profile=<id>`，直接选中对应方案。

### 模板与高级正则

匹配对象为标准化后**去掉扩展名、保留全部括号、标签和编号**的名称。支持 `{artist}`、`{title}`、`{code}`、`{language}`、`{version}`、`{quality}`、`{extra}`；artist/title 必需，code 只能是数字且保留前导零。

模板示例：`{artist}-{title}`、`{title}-{artist}`、`{artist}《{title}》`、`{code} {artist} {title}`、`{artist}-{title}-{language}-{extra}`。字段不可重复或相邻；分隔符按字面匹配。同一模板存在多个有效拆分时不任意选择，例如 `A-B-C` 可以分成 `A / B-C` 或 `A-B / C`，会展示歧义候选。复杂模板最多探索 20,000 个拆分步骤，达到上限明确提示，不选择已找到的任意候选。

高级正则使用固定依赖 RE2/J 1.8，在服务端全串匹配，前端不另写解析器：

```regex
(?P<title>[^-]+)-(?P<artist>[^-]+)
```

必须有 artist/title 命名组，可选命名组与模板字段一致。未知命名组、不支持的语法、回溯引用和前后查找断言会被拒绝；可以使用普通分组、贪婪/非贪婪量词明确表达捕获含义。表达式本身不做文件名字符标准化。

沿用单条名称最多 100 字符、表达式最多 2,048 字符、预览文件名最多 1,024 字符。规则库最多 1,000 个定义，最多 100 个方案，每个方案最多 100 个不同成员；启用状态和顺序只属于方案。不执行脚本或宿主代码。

## 解析方案：组合与预览

主路径只有三步，页面不出现“规则库/定义/表达式”这些概念：

1. 在“你的文件名长这样？”里填一个真实文件名，点“找出能识别的格式”。前端把全部内置格式作为一次草稿预览的 `entries` 发给 `POST /profiles/preview`，用返回的逐条 `attempts` 状态判断哪种格式识别得动，不需要单独的检测接口。
2. 在“常见命名格式”卡片墙里点一张卡，即把对应内置定义追加为方案成员；再点一次移出。能完整识别的排在前面并标“能用”，部分命中、有歧义和识别不了依次降序置灰，已在方案里的高亮。
3. 检查名称后点“保存方案”。保存校验引用有效性、重复成员和规则定义，不要求示例文本一定命中；空方案也可以保存。

第 4 步“我要自己排顺序、启停，或用自定义格式”折叠在 `<details>` 里，行为与之前一致：有序成员列表、上移/下移/移除、启停、从规则库添加已有定义（选项已排除内置格式，改由卡片墙提供）、以及“解析当前方案”的完整预览。表达式只读，修改定义需前往规则库。顶层预览只显示歌手、歌名和命中结论，标准化名称、实际匹配文本、结构置信度、未识别片段、逐规则表格与完整 JSON 收进“匹配细节与完整解析 JSON”折叠区。检测只在点击时发起；改文件名只把旧检测结果标记为过期，不自动重跑。

复制方案只复制引用、启用状态和顺序，不复制规则定义。移除成员或删除方案不删除共享规则。已有“默认解析方案”的 ID 固定为 `default`，用于兼容旧接口，可改名或清空成员，不能删除；其他方案可删除。

第一条完整解析出有效歌手和歌名的规则生效；前面的部分匹配、多解不阻止后续完整匹配，页面仍展示所有尝试。空方案、全部停用或全部未命中时明确显示未命中，不调用旧解析器猜测。附加标签未知或冲突不降低已确认核心字段状态，证据继续保留；结果是符合指定命名规则，不是身份识别正确率。

未保存成员或名称有变化时显示“未保存草稿”。切换方案、离开页面会询问是否放弃；刷新/关闭触发浏览器离开保护。编辑输入或方案后旧预览标记过期，迟到响应丢弃。冲突时加载最新规则库及修订号可以保留当前草稿；若成员已被删除，会明确显示失效引用并要求用户处理，不自动删除成员。原方案已被其他窗口删除时，保留草稿为新方案。

## 存储格式与兼容升级

SQLite/MySQL 的 `202611070001_initial_schema.sql` 直接定义 `filename_parser_config` 单例表。revision、updated_at 用于配置并发控制，rules_json 支持将旧数组按运行时兼容逻辑转换为：

```json
{
  "schemaVersion": 2,
  "definitions": [
    {"id":"reverse","name":"反向","type":"TEMPLATE","expression":"{title}-{artist}","builtinId":null}
  ],
  "profiles": [
    {"id":"default","name":"默认解析方案","entries":[{"ruleId":"reverse","enabled":true}]}
  ]
}
```

读取旧数组时只生成兼容视图：保留原 ID、名称、类型、表达式，原顺序和 enabled 进入默认方案。GET 和预览不落库；第一次成功修改才在修订号校验下原子写入 schemaVersion=2。空配置表继续返回内置默认定义及方案，不自动插入记录。损坏配置、未知版本或校验失败不得覆盖原数据或静默回退。

所有定义/方案修改共享一个全局 revision。引用检查与更新基于同一文档快照，再通过 revision 条件更新，防止“删除规则”和“新增引用”并发留下悬空引用。任意窗口成功修改都会让其他旧快照写入或草稿预览返回 409；已开始的一次解析保持自己的不可变快照。

## API

前缀 `/api/v1/filename-parser`，所有接口需要管理员会话，POST/PUT/DELETE 需要 CSRF。

| 接口 | 行为 |
|---|---|
| GET `/rule-definitions`、GET `/profiles` | 同一完整 Workspace：revision、updatedAt、definitions（含 referencedBy）、profiles、builtinRules、changedId=null |
| POST `/rule-definitions` | `{revision, definition:{name,type,expression,builtinId:null}}`，创建自定义定义；201 返回新 Workspace |
| PUT `/rule-definitions/{id}` | 同样请求，编辑共享定义；200 返回新 Workspace |
| DELETE `/rule-definitions/{id}?revision=…` | 删除未引用自定义定义；200 返回新 Workspace |
| POST `/profiles` | `{revision, profile:{name,entries:[{ruleId,enabled}]}}`，201 返回新 Workspace |
| PUT `/profiles/{id}` | 修改名称和整个有序成员列表；200 返回新 Workspace |
| DELETE `/profiles/{id}?revision=…` | 删除非默认方案，保留定义；200 返回新 Workspace |
| POST `/profiles/preview` | 已保存来源 `{filename,profileId}` 或草稿 `{filename,revision,entries}`，不得混用；200 返回 `{revision,profileId,profileName,preview}` |

修改响应的 changedId 标识本次操作对象，创建 ID 由服务端生成。新方案预览必须明确来源，没有隐式默认选择。草稿预览中 profileId/profileName 为 null，页面使用草稿名称展示。空 entries 明确表示不执行规则。

400 使用 Problem Details 并定位 ruleId/ruleIndex/field；引用阻止删除的 409 包含 referencedBy 数组（方案 ID/名称）；修订冲突同为 409；对象不存在为 404。全部契约见 [OpenAPI](../contracts/openapi/singverse.openapi.json)。

### 旧接口保留

- GET `/rules`：返回默认方案展开后的旧 Configuration 结构（成员 enabled 重新合入执行定义）。
- PUT `/rules`：检查全局 revision，合并规则定义并替换默认方案成员；省略的定义只退出默认方案，不删除共享库或其他方案引用。内置定义不可改写。
- POST `/preview`：显式完整规则草稿行为保持；省略/null rules 使用默认方案，`[]` 无规则且不兜底。
- `new KtvFilenameParser().parse(filename)`：保持原纯逻辑行为，不读取方案或数据库。已有 `parse(filename, FilenameRuleSet)` 和 `preview(...)` 继续接受不可变的展开规则。

## 验证

`make frontend-check`、`make go-backend-test`、`make web-check` 沿用现有入口。HTTP 测试验证迁移、共享更新、引用保护、方案 CRUD、旧接口、并发竞争与权限；可选 `mysql-check` 中的 `MySqlFilenameParserIT` 复用相同断言，仅对独立测试库运行。实际检查和浏览器/重启验收结果见[验证记录](verification.md)。

被媒体来源引用的解析方案不能删除，须先修改或移除来源绑定。方案改名、成员启停/排序、共享规则编辑只影响下次扫描；已保存歌曲元数据保留当时的配置修订号，重新扫描后更新。


## 音频文件扫描与在线播放

本地目录与 WebDAV 文件名扫描统一支持 MP3、FLAC、WAV、M4A、AAC、OGG、OGA、Opus、WMA、AIF、AIFF、APE，扩展名不区分大小写，沿用现有解析方案提取歌名和歌手。扫描只建立文件名索引，不读取音频内容或内嵌标签；新增格式需重新扫描才会进入歌曲列表，现有扫描数量限制不变。

“在线播放”按扩展名选择音频/视频控件。音频复用已鉴权的 GET/HEAD `/api/v1/media-songs/{id}/video`（为兼容保留原路径），返回 audio/* 媒体类型，沿用 Range、后端 WebDAV 代理、凭据保护与关闭停止加载。音频失败可重试，切换或关闭时移除旧媒体元素。不提供转码，扩展名可入库不保证浏览器能解码；WMA、APE 等通常需要其他播放器。WebDAV 下载重定向限制不变。
