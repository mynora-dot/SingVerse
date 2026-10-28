# API 契约约定

首批业务 API 为单管理员账号、会话和 MFA，见 [singverse.openapi.json](singverse.openapi.json)（OpenAPI 3.1）。客户端与未来 Go 服务端遵循同一接口；后续仅为真实功能扩充契约。

- 路径版本：未来业务接口以 `/api/v1` 开始。破坏性变化使用新主版本；增加可选字段一般保持当前版本。
- JSON 字段统一 lower camelCase；不暴露 Java 类名、Entity、堆栈或语言专属对象。
- 错误采用 RFC 9457 Problem Details，媒体类型 `application/problem+json`，使用标准 `type`、`title`、`status`、`detail`、`instance` 字段。具体业务错误标识在真实接口出现时定义，不向客户端泄露凭据和内部实现。
- HTTP 状态码表达实际结果：200 查询/更新，201 创建，204 无响应体成功；400 参数错误，401 未认证，403 无权限，404 不存在，409 状态冲突，500 服务异常，503 服务暂不可用。不以 HTTP 200 包裹错误。
- 列表采用 `page`（从 0 开始）与 `size`，响应包含 `items`、`page`、`size`、`totalElements`。每个真实列表接口须明确默认/最大页大小与稳定排序字段，避免当前虚构业务规则。
- 时间采用 ISO 8601 UTC 字符串，以 `Z` 结尾；数据库时间与客户端展示时区分离。
- 向后兼容：不随意删除字段、改变类型/含义或新增必填参数；客户端允许忽略未知字段。变更需契约评审与兼容性测试。
- Java/Go 使用同一契约样例和测试，覆盖状态码、错误结构、分页、时间与空值规则；重构前先建立这些真实接口的兼容性基线。

## 计算节点内部契约

`compute-internal.openapi.json`是已实现的Python节点探测接口契约（OpenAPI 3.1），由FastAPI的`app.openapi()`生成；`make compute-check`验证它与实际HTTP返回的`/openapi.json`逐项一致。内部调用由Java发起，不提供给四端直接调用：

- `GET /health`：仅进程健康，`{"status":"UP"}`。
- `GET /internal/v1/capabilities`：`service=singverse-compute`、`apiVersion=1`、`capabilities=[]`；空列表是当前真实状态，不能推出支持转码或AI。Java同时核对服务身份与协议版本。
- 错误路由和不支持的方法返回真实404/405及`application/problem+json`。没有占位任务提交/进度/取消接口。

`make backend-check`另行验证真实Python进程与Java客户端的协议兼容和上下线隔离；singverse-backend CI随任一后端或内部契约变更执行。内部服务地址来自部署配置，不向四端提供节点管理或直连能力。

这不是首批业务API。未来首个真实计算任务实现时再补充受认证的任务契约、稳定任务ID、幂等键、输入输出引用、状态/取消/重试及资源限制，并与Java共用契约验证；不将“接收请求”描述成任务执行完成。

`/api/health` 是当前运维健康端点，保留 Spring Boot Actuator 原生响应，不属于未来业务 API 的错误或分页约定。

统一网页前端独立于服务端语言，当前管理功能通过上述账号与 MFA 业务 API 登录，运维健康仍独立。Java/未来Go的静态资源路径、hash路由和健康兼容行为见[前端托管契约](../frontend-hosting.md)；前端不得直连Python内部端点或数据库。

## 媒体来源管理

`singverse.openapi.json` 新增 `/api/v1/media-sources` 列表/创建及 `/{id}` 查询/更新/移除，继承管理员 Cookie 会话、同源及 CSRF。列表默认 20、最大 100，按创建时间倒序与 ID 升序；响应不含密码，编辑省略密码保留、空值字符串清除。来源通过 `parserProfileId` 绑定已保存方案，本地可通过 `/{id}/scan` 扫描并用 `/{id}/songs` 分页读取已保存歌曲数据；WebDAV 没有连接测试或扫描；详见[媒体库说明](../../docs/media-library.md)。

## 文件名解析规则配置与预览

新增 GET/PUT `/api/v1/filename-parser/rules` 及 POST `/api/v1/filename-parser/preview`。管理员会话保护全部接口，写入及草稿预览需要 CSRF。配置整体更新使用 revision，冲突返回 409；400 规则校验通过 Problem Details 的 ruleId/ruleIndex/field 定位。

预览传入 rules 使用草稿，省略或 null 使用保存配置，空数组明确禁用所有规则。正常未命中返回 200 和 selectedRule=null，不触发隐藏兜底；attempts 显示每条规则的状态、捕获与歧义候选。规则匹配不等于歌曲身份识别；详情见[规则说明](../../docs/filename-rules.md)。

## 共享规则库与解析方案

新增 `/filename-parser/rule-definitions`、`/profiles` 的 GET/POST 与 `/{id}` PUT/DELETE，以及 `/profiles/preview`（均在 `/api/v1` 下）。所有读取/修改返回统一 Workspace，包含共享定义、引用方案、方案成员和全局 revision；创建后 changedId 指向新对象。DELETE 使用 revision 查询参数，引用保护 409 包含 referencedBy。

方案预览明确选择 profileId 或带 revision 的 entries 草稿；不混用，不隐式默认，不落库。旧三个接口继续兼容默认方案，旧 PUT 合并定义、只替换默认方案成员，未出现的定义不删除。GET 不持久化旧数据格式投影；详情见[规则与方案](../../docs/filename-rules.md)。

## 歌曲管理

`GET /api/v1/media-songs` 汇总已保存的本地扫描歌曲，复用管理员会话，支持 `sourceId`、文件名搜索 `q`、复核状态 `status=ALL|REVIEW|MATCHED` 及 `page/size`。响应复用 MediaSongPage，歌曲包含来源名称；所有条件在数据库分页前执行。它不会触发扫描或播放，也不会访问 WebDAV。

## 歌曲元数据刮削

新增 `/api/v1/metadata` 下的设置、单曲搜索/应用、批次创建/查询/暂停/续跑/终止、明细重试/审核，以及候选/已确认封面代理。功能与自动写入默认关闭，单曲搜索只产生候选；所有操作需要管理员会话，非 GET 操作需要 CSRF。`MediaSong.metadata` 为可空的独立确认覆盖层，失效时不可使用其值覆盖原始 `result`。批次完成与审核完成分开，最新批次不存在返回 204。字段、枚举、分页及路径以 JSON 契约为准，详细限制见[元数据刮削](../../docs/metadata-scraping.md)。

歌曲位置页面改用卡片；来源列表 API 保留名称/地址、类型、方案筛选和分页，歌曲列表继续使用搜索与分页表格。查询使用 ID 分页后延迟关联和筛选排序联合索引。深分页成本与包含搜索边界见[列表查询说明](../../docs/library-search.md)。

媒体来源添加/编辑支持定时扫描开关及标准五段 cron（上海时区）；配置与下次触发持久化，同来源扫描中跳过，停机遗漏不逐次补跑。使用及 API 字段、调度限制见[定时扫描](../../docs/media-scan-schedule.md)。

单曲元数据已使用独立双栏审核工作台，支持字段编辑、受管 JPEG/PNG 上传、平台封面搜索及统一确认应用；旧批量审核入口保持独立。新增接口、资源备份、幂等与版本校验规则见[单曲审核](../../docs/single-song-review.md)。

批量工作台已统一整体进度：total/completed 均包含跳过项，新批次持久化跳过明细，旧批次仅汇总并提示。新增批次内搜索/排序、可信度审核提示及按状态操作，自动写入和限流规则不变。接口兼容说明及统计定义见[批量工作台](../../docs/batch-metadata-workbench.md)。

歌曲分类契约新增 `/api/v1/classification` 及 `Classification*` schemas；媒体歌曲响应包含分类状态，查询支持 `tagIds`（维度内 OR、维度间 AND）及 `unclassified`。所有接口继续要求管理员会话，写入使用 CSRF；没有新增访客公开分类接口。操作语义见[分类说明](../../docs/song-classification.md)。


## 歌曲位置与本地目录选择

管理端“歌曲位置”以卡片呈现来源，统一添加入口；定时扫描、解析方案收进“更多设置”。本地文件夹通过 `GET /api/v1/media-sources/local-directories?path=...&page=0` 浏览选择，WebDAV 仍填写地址。名称在前端留空时按文件夹名生成，API 的 name 仍为必填。

目录接口仅管理员会话可用，范围与已有本地来源保存相同：服务进程有权读取的目录，不改变权限或挂载。空 path 列出文件系统根目录；绝对路径返回规范化 path、parent、items（name/path）、hasMore。parent 为 null 表示根目录列表，为空字符串表示返回该列表。仅列可读子目录，略过符号链接，不读文件内容；按目录名排序，每页固定 100 条，page 从 0 开始且不超过 10000。路径无效、不存在或无法列目录返回 400，未登录返回 401。Docker 中仅能看到容器目录，NAS 歌曲目录须预先只读挂载，不能通过浏览器选择未挂载的宿主机目录。


## 管理端在线播放

歌曲列表行内操作统一为按钮，新增“在线播放”，在带原生控件的视频弹窗中播放当前歌曲。关闭弹窗或离开页面移除 video 元素以停止读取；错误时可重新加载。

`GET /api/v1/media-songs/{id}/video` 与 HEAD 复用管理员 Cookie 会话，未登录返回 401，不提供公开媒体 URL。支持单段 Range（起止、开放结束、后缀范围），本地响应 200/206/416 和 Content-Length/Content-Range；非法范围或多范围返回 400。所有流响应禁止缓存。本地解析真实路径并限制在来源目录内，失效文件或越界符号链接返回 404。

WebDAV 使用保存的地址和加密凭据在服务端发起 GET/HEAD，转发 Range，不把凭据或远端地址返回浏览器。采用 10 秒连接、30 秒读取空闲超时，流式传输，不落盘、不完整缓冲；连接随响应结束/客户端断开清理。上游忽略 Range 时保持 200，拖动能力取决于上游；不跟随 3xx（返回 502），需要支持直接读取的 WebDAV 服务，OpenList 等返回下载跳转的配置需调整，否则暂不能播放。认证/读取错误返回不含远端细节的 502。

只播放原始音视频，不调用 Python、不转码、不创建队列或电视会话。浏览器能否解码取决于容器与视频/音频编码，MP4/WebM 也不保证所有编码都能播放，MKV/AVI 等可能不可播放。Nginx 对视频接口关闭 proxy_buffering/proxy_cache，避免视频临时文件与额外等待；代理读取空闲超时为 60 秒。真实 NAS、WebDAV 提供者、浏览器编码兼容性仍须单独验收。


## 音频文件扫描与在线播放

本地目录与 WebDAV 文件名扫描统一支持 MP3、FLAC、WAV、M4A、AAC、OGG、OGA、Opus、WMA、AIF、AIFF、APE，扩展名不区分大小写，沿用现有解析方案提取歌名和歌手。扫描只建立文件名索引，不读取音频内容或内嵌标签；新增格式需重新扫描才会进入歌曲列表，现有扫描数量限制不变。

“在线播放”按扩展名选择音频/视频控件。音频复用已鉴权的 GET/HEAD `/api/v1/media-songs/{id}/video`（为兼容保留原路径），返回 audio/* 媒体类型，沿用 Range、后端 WebDAV 代理、凭据保护与关闭停止加载。音频失败可重试，切换或关闭时移除旧媒体元素。不提供转码，扩展名可入库不保证浏览器能解码；WMA、APE 等通常需要其他播放器。WebDAV 下载重定向限制不变。
