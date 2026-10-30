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


## AI 配置

管理员专用 `/api/v1/ai` 提供厂商目录、多模型/搜索服务 CRUD、显式连接与结构化测试、默认搜索和功能绑定。密钥只写不读，服务与绑定使用 revision 并发保护，引用中的服务不能删除或停用。原分类配置接口保留兼容适配；边界与通用搜索响应见 [AI 设置](../../docs/ai-settings.md)，字段以 `singverse.openapi.json` 为准。

本地扫描沿用歌曲API元数据结构，字段来源kind新增FILE，表示本地音视频内嵌标签优先值；不扩展WebDAV读取或增加远程探测API。

计算服务新增POST /internal/v1/media/tags（认证、根目录限制、有限并发）及Tags类型，见compute-internal.openapi.json。Java仅内部调用，前端不访问计算节点。

- 管理员性能节点：`GET/PUT /api/v1/compute-node` 和 `POST /api/v1/compute-node/test`；写入和测试要求 CSRF，凭据仅写，AUTO 解析部署变量、MANUAL 使用加密配置、DISABLED 停用。测试失败使用 Check.status 表达，不返回上游原始错误或触发管理员登出。
- 计算内部 `GET /internal/v1/status` 验证 Bearer 凭据后返回身份和实际能力；与公开进程健康及兼容能力探测分离。

## TV 与亲友聚会

新增 TV/party 契约见 `singverse.openapi.json` 与 [TV 开发设计](../../docs/tv-development-design.md)。`/server-info` 提供稳定实例身份；电视先申请十分钟授权，管理员在 `/admin/#/system/tv-devices` 核对短码并批准。轮询秘密换取独立设备 token，重复领取返回同一凭据，数据库只存摘要。设备 token 不授予管理员权限；撤销设备后媒体及关联聚会访问立即失效。

`/tv/**` 和 `/party/**` 只接受 Authorization Bearer，不接受管理员 Cookie。这两个独立命名空间不使用 CSRF；管理端继续 Cookie + CSRF。亲友邀请预览使用 POST 读取以避免令牌进入 URL 访问日志，预览不加入；显式 join 获得十二小时参与凭据，昵称不决定权限。邀请有效一小时，刷新使旧邀请失效，散场失效全部参与授权。

聚会变更包含 requestId、expectedRevision；同主体同请求与载荷重试不重复入队，冲突 409 后先刷新。revision 是用户操作修订号，播放遥测有独立数据库 CAS 与 eventSeq，不让周期进度报告制造点歌冲突。NEXT 进入待确认交接，START 仅电视可执行；播放事件才使队列变为 PLAYING。电视进程重建调用 recover，失效旧播放实例、清理命令，将进行项放回待唱，不自动重播。

当前仅原始媒体直放，无转码。播放资源 READY 表示已取得授权引用，不保证目标电视能解码；原始媒体直接使用设备 Bearer 请求，并在每次 Range/HEAD 时重新检查撤销。歌曲与分类只读投影不暴露内部路径、来源凭据或模型配置。歌手入口按原始署名精确分组，不建立身份或别名归并。家庭规模保护上限：单场 500 个队列项、1000 项幂等操作、100 条待确认命令；超限明确拒绝。TV 需结束后开新场，不静默删除历史去重记录。

### TV 首页与当前歌曲详情

`GET /api/v1/tv/home` 仅设备可读，返回稳定服务身份 `server`、全库现存歌曲数 `totalSongs`、按扫描时间倒序/ID 升序的前 8 首 `recentSongs` 和 `generatedAt`。它不接收找歌筛选参数，不以推荐、热度或演唱历史名义包装扫描顺序。

`GET /api/v1/tv/library/songs/{id}` 读取当前歌曲展示投影，歌曲移除返回 404。投影沿用已应用元数据/文件标签与文件名解析的现有优先级，新增可用的 album、releaseDate、durationMs、scannedAt 与不透明 revision；无依据字段省略或 null，不补假值。revision 包含元数据和分类变化，供封面重载使用，不暴露路径或来源凭据。所有设备/参与者仍遵循现有授权；首页不对参与者开放。

TV 非转码增强新增设备专用 text-tracks 与 releases/current、releases/apk；直放资源含 revision。文字检索读取有效元数据，支持规范化全拼、首字母及精确编号；共享读索引最长约30秒更新，分类先过滤再分页。接口定义只覆盖实际读取/发布能力，不承诺转码或自动轨道语义。

聚会快照现同时返回 stateRevision（包含播放事件的持久版本）和 revision（队列乐观并发版本）；TV 用前者防止旧快照覆盖，用后者提交队列命令。换会话/NAS 的迟到响应被忽略。

分类封面新增管理员GET/PUT/DELETE /classification/tags/{id}/cover，以及Bearer只读 /tv/library/classification-tags/{id}/cover。首次上传version为空，覆盖/移除带当前coverVersion；内容SHA-256与标签revision独立。正式标签新增coverPath/coverVersion；停用标签对TV封面返回404，完整限制见OpenAPI。

歌手库改为自动汇总：旧档案创建/编辑、人工绑定写操作返回 410。歌曲订正通过现有审核应用接口保存 NAS 歌手覆盖，恢复接口需要 revision。歌手响应新增 automatic、fetchState、图片来源字段；ExternalTrack 新增可选 artistRefs，旧数据按空列表兼容。详见 OpenAPI。

共享歌单新增 `/api/v1/playlists` 管理接口、`/generate` 建议预览及 TV 只读歌单列表/详情/歌曲接口。创建需稳定 requestId，编辑与删除需 revision；生成返回候选范围和草稿，不自动保存。管理与 TV 封面路径分别使用相应身份鉴权。

## 转码与播放协商

新增 `/api/v1/transcoding` 管理端接口、`/api/v1/tv/transcoding` 设备会话资源和 `/api/v1/internal/transcode-input/{id}` 计算节点专用 Range 读取。客户端能力为运行时报告，实时返回 DIRECT/VERSION/HLS 与原媒体秒偏移；HLS 准备中需轮询会话并续租。所有播放资源仍经过 Java 授权，内部执行接口只对 Java 开放。离线任务持久化在业务库，成功文件由 Java 校验长度/SHA-256 后原子保存。详细行为、错误和部署配置见[转码说明](../../docs/transcoding.md)。

硬件加速配置：GET/POST `/api/v1/transcoding/hardware-settings`，POST `/hardware-settings/test`。保存携带 revision；冲突返回 409，指定硬件短样本失败返回 422。`backend=deployment` 恢复节点部署环境变量，草稿测试不切换当前后端。内部接口路径对应 `/internal/v1/transcoding/hardware-settings`，继续使用 Bearer 和请求体边界。

流水线 v2：播放执行携带 `pipeline`（实际硬解/零拷贝/GPU 色调映射、DV 和回退原因）及 `sampledPeakRssBytes`（0 表示未知）。客户端能力增加 `hdr_formats`、`dovi_profiles`、`dovi_compatibility`，默认不支持 DV；HDR 与 DV 能力分别协商。

手机原生只读曲库：GET `/api/v1/mobile/library/songs`、`/{id}` 和 `/{id}/text-tracks`，使用管理员 Cookie，不接受 TV/party Bearer 替代登录。列表复用既有搜索及分类，返回 MobileSongPage；条目保留独立媒体 ID，补充格式/媒体类型，封面指向管理员资源。播放继续使用 `/api/v1/transcoding`；手机本机收藏和历史不属于服务端业务表。
