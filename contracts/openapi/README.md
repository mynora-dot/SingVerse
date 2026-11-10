# API 契约约定

首批业务 API 为单管理员账号、会话和 MFA，见 [singverse.openapi.json](singverse.openapi.json)（OpenAPI 3.1）。客户端与Go 服务端遵循同一接口；后续仅为真实功能扩充契约。

- 路径版本：未来业务接口以 `/api/v1` 开始。破坏性变化使用新主版本；增加可选字段一般保持当前版本。
- JSON 字段统一 lower camelCase；不暴露 Go 类名、Entity、堆栈或语言专属对象。
- 错误采用 RFC 9457 Problem Details，媒体类型 `application/problem+json`，使用标准 `type`、`title`、`status`、`detail`、`instance` 字段。具体业务错误标识在真实接口出现时定义，不向客户端泄露凭据和内部实现。
- HTTP 状态码表达实际结果：200 查询/更新，201 创建，204 无响应体成功；400 参数错误，401 未认证，403 无权限，404 不存在，409 状态冲突，500 服务异常，503 服务暂不可用。不以 HTTP 200 包裹错误。
- 列表采用 `page`（从 0 开始）与 `size`，响应包含 `items`、`page`、`size`、`totalElements`。每个真实列表接口须明确默认/最大页大小与稳定排序字段，避免当前虚构业务规则。
- 时间采用 ISO 8601 UTC 字符串，以 `Z` 结尾；数据库时间与客户端展示时区分离。
- 向后兼容：不随意删除字段、改变类型/含义或新增必填参数；客户端允许忽略未知字段。变更需契约评审与兼容性测试。
- Go/Go 使用同一契约样例和测试，覆盖状态码、错误结构、分页、时间与空值规则；重构前先建立这些真实接口的兼容性基线。

## 计算节点内部契约

`compute-internal.openapi.json`是已实现的Python节点探测接口契约（OpenAPI 3.1），由FastAPI的`app.openapi()`生成；`make compute-check`验证它与实际HTTP返回的`/openapi.json`逐项一致。内部调用由Go发起，不提供给四端直接调用：

- `GET /health`：仅进程健康，`{"status":"UP"}`。
- `GET /internal/v1/capabilities`：`service=singverse-compute`、`apiVersion=1`、`capabilities=[]`；空列表是当前真实状态，不能推出支持转码或AI。Go同时核对服务身份与协议版本。
- 错误路由和不支持的方法返回真实404/405及`application/problem+json`。没有占位任务提交/进度/取消接口。

`make backend-check`另行验证真实Python进程与Go客户端的协议兼容和上下线隔离；singverse-backend CI随任一后端或内部契约变更执行。内部服务地址来自部署配置，不向四端提供节点管理或直连能力。

这不是首批业务API。未来首个真实计算任务实现时再补充受认证的任务契约、稳定任务ID、幂等键、输入输出引用、状态/取消/重试及资源限制，并与Go共用契约验证；不将“接收请求”描述成任务执行完成。

`/api/health` 是当前运维健康端点，保留兼容的整体健康 JSON 响应，不属于未来业务 API 的错误或分页约定。

统一网页前端独立于服务端语言，当前管理功能通过上述账号与 MFA 业务 API 登录，运维健康仍独立。Go/Go的静态资源路径、hash路由和健康兼容行为见[前端托管契约](../frontend-hosting.md)；前端不得直连Python内部端点或数据库。

## 媒体来源管理

`singverse.openapi.json` 新增 `/api/v1/media-sources` 列表/创建及 `/{id}` 查询/更新/移除，继承管理员 Cookie 会话、同源及 CSRF。列表默认 20、最大 100，按创建时间倒序与 ID 升序；响应不含密码，编辑省略密码保留、空值字符串清除。来源通过 `parserProfileId` 绑定已保存方案，本地可通过 `/{id}/scan` 扫描并用 `/{id}/songs` 分页读取已保存歌曲数据；WebDAV 使用后台扫描任务，目录可列出不代表媒体可分段读取或播放；详见[媒体库说明](../../docs/media-library.md)。

## 文件名解析规则配置与预览

新增 GET/PUT `/api/v1/filename-parser/rules` 及 POST `/api/v1/filename-parser/preview`。管理员会话保护全部接口，写入及草稿预览需要 CSRF。配置整体更新使用 revision，冲突返回 409；400 规则校验通过 Problem Details 的 ruleId/ruleIndex/field 定位。

预览传入 rules 使用草稿，省略或 null 使用保存配置，空数组明确禁用所有规则。正常未命中返回 200 和 selectedRule=null，不触发隐藏兜底；attempts 显示每条规则的状态、捕获与歧义候选。规则匹配不等于歌曲身份识别；详情见[规则说明](../../docs/filename-rules.md)。

## 共享规则库与解析方案

新增 `/filename-parser/rule-definitions`、`/profiles` 的 GET/POST 与 `/{id}` PUT/DELETE，以及 `/profiles/preview`（均在 `/api/v1` 下）。所有读取/修改返回统一 Workspace，包含共享定义、引用方案、方案成员和全局 revision；创建后 changedId 指向新对象。DELETE 使用 revision 查询参数，引用保护 409 包含 referencedBy。

方案预览明确选择 profileId 或带 revision 的 entries 草稿；不混用，不隐式默认，不落库。旧三个接口继续兼容默认方案，旧 PUT 合并定义、只替换默认方案成员，未出现的定义不删除。GET 不持久化旧数据格式投影；详情见[规则与方案](../../docs/filename-rules.md)。

## 歌曲管理

`GET /api/v1/media-songs` 汇总已保存的本地扫描歌曲，复用管理员会话，支持 `sourceId`、文件名搜索 `q`、复核状态 `status=ALL|REVIEW|MATCHED` 及 `page/size`。响应复用 MediaSongPage，歌曲包含来源名称；所有条件在数据库分页前执行。它不会触发扫描或播放，也不会访问 WebDAV。

## 歌曲元数据刮削

新增 `/api/v1/metadata` 下的设置、单曲搜索/应用、批次创建/查询/暂停/续跑/终止、明细重试/审核，以及候选/已确认封面代理。批次创建可在 `all=true` 时提供 `sourceId`，仅处理该媒体来源。新安装默认启用网易云、QQ、酷狗、酷我和汽水五个平台，自动写入默认关闭；升级保留已保存的配置。管理端入口为“设置 → 音乐平台”，单曲搜索只产生候选；所有操作需要管理员会话，非 GET 操作需要 CSRF。`MediaSong.metadata` 为可空的独立确认覆盖层，失效时不可使用其值覆盖原始 `result`。批次完成与审核完成分开，最新批次不存在返回 204。字段、枚举、分页及路径以 JSON 契约为准，详细限制见[元数据刮削](../../docs/metadata-scraping.md)。

歌曲位置页面改用卡片；来源列表 API 保留名称/地址、类型、方案筛选和分页，歌曲列表继续使用搜索与分页表格。查询使用 ID 分页后延迟关联和筛选排序联合索引。深分页成本与包含搜索边界见[列表查询说明](../../docs/library-search.md)。

媒体来源添加/编辑支持定时扫描开关及标准五段 cron（上海时区）；配置与下次触发持久化，同来源扫描中跳过，停机遗漏不逐次补跑。使用及 API 字段、调度限制见[定时扫描](../../docs/media-scan-schedule.md)。

单曲元数据已使用独立双栏审核工作台，支持字段编辑、受管 JPEG/PNG 上传、平台封面搜索及统一确认应用；批次候选审核复用同一工作台，确认后同步更新批次明细。新增接口、资源备份、幂等与版本校验规则见[单曲审核](../../docs/single-song-review.md)。

批量工作台已统一整体进度：total/completed 均包含跳过项，新批次持久化跳过明细，旧批次仅汇总并提示。新增批次内搜索/排序、可信度审核提示及按状态操作，自动写入和限流规则不变。接口兼容说明及统计定义见[批量工作台](../../docs/batch-metadata-workbench.md)。

歌曲分类契约新增 `/api/v1/classification` 及 `Classification*` schemas；媒体歌曲响应包含分类状态，查询支持 `tagIds`（维度内 OR、维度间 AND）及 `unclassified`。所有接口继续要求管理员会话，写入使用 CSRF；没有新增访客公开分类接口。操作语义见[分类说明](../../docs/song-classification.md)。


## 歌曲位置与本地目录选择

歌曲下载新增 `/api/v1/lx-downloads` 和 `Lx*` schemas，媒体来源增加 `useForLxDownloads`。全部要求管理员会话，POST/DELETE 要求 CSRF；请求只接收服务端缓存的歌曲标识，不接受音频 URL 或解析载荷。目录搜索页码从 1 开始，记录页码从 0 开始；预览显示上游总数、读取限制及警告。音源正文与导入地址仅写入并加密，响应不回显。下载执行独立于只读任务中心，取消/重试通过下载业务接口。完整语义见[歌曲下载](../../docs/lx-downloads.md)。

管理端“歌曲位置”以卡片呈现来源，统一添加入口；定时扫描、解析方案收进“更多设置”。本地文件夹通过 `GET /api/v1/media-sources/local-directories?path=...&page=0` 浏览选择，WebDAV 仍填写地址。名称在前端留空时按文件夹名生成，API 的 name 仍为必填。

目录接口仅管理员会话可用，范围与已有本地来源保存相同：服务进程有权读取的目录，不改变权限或挂载。空 path 列出文件系统根目录，并通过 `locations` 返回常用目录与实际挂载目标的读取/写入状态；`containerized` 表示检测到容器标记。只返回后端可见路径，不回显宿主机挂载来源，不需要 Docker socket；非空 path 的 `locations` 为空列表。绝对路径返回规范化 path、parent、items（name/path）、hasMore。parent 为 null 表示根目录列表，为空字符串表示返回该列表。仅列可读子目录，略过符号链接，不读文件内容；可选 `q` 在排序和分页前按目录名不区分大小写筛选，最长 100 字。每页固定 100 条，page 从 0 开始且不超过 10000。写入状态仅供提示，下载绑定仍实际探测写入。路径无效、不存在或无法列目录返回 400，未登录返回 401。Docker 中仅能看到容器目录，NAS 歌曲目录须预先挂载。


## 管理端在线播放

歌曲列表行内操作统一为按钮，新增“在线播放”，在带原生控件的视频弹窗中播放当前歌曲。关闭弹窗或离开页面移除 video 元素以停止读取；错误时可重新加载。

`GET /api/v1/media-songs/{id}/video` 与 HEAD 复用管理员 Cookie 会话，未登录返回 401，不提供公开媒体 URL。支持单段 Range（起止、开放结束、后缀范围），本地响应 200/206/416 和 Content-Length/Content-Range；非法范围或多范围返回 400。流响应发送 HTTP `private, no-store`，避免浏览器与公共代理缓存；Go 内部受控的持久原片缓存独立管理。本地解析真实路径并限制在来源目录内，失效文件或越界符号链接返回 404。

WebDAV 使用保存的地址和加密凭据在服务端发起 GET/HEAD，转发 Range，不把凭据或远端地址返回浏览器。采用 10 秒连接、30 秒读取空闲超时，流式传输、不在内存完整缓冲；可将完整原片块持久写入 Go 独立缓存，缓存不可用时继续回源；连接随响应结束/客户端断开清理。上游忽略从零开始的 Range 时仍可返回 200；GET 忽略非零或后缀 Range 时立即返回 502，避免播放器反复下载并跳过整段前缀。206 响应须与请求的起止、总长及 Content-Length 一致，非 identity 编码响应拒绝交给播放器；不跟随 3xx（返回 502），需要支持直接读取的 WebDAV 服务，OpenList 等返回下载跳转的配置需调整，否则暂不能播放。认证/读取错误返回不含远端细节的 502。

只播放原始音视频，不调用 Python、不转码、不创建队列或电视会话。浏览器能否解码取决于容器与视频/音频编码，MP4/WebM 也不保证所有编码都能播放，MKV/AVI 等可能不可播放。Nginx 对视频接口关闭 proxy_buffering/proxy_cache，避免视频临时文件与额外等待；代理读取空闲超时为 60 秒。真实 NAS、WebDAV 提供者、浏览器编码兼容性仍须单独验收。


## 音频文件扫描与在线播放

本地目录与 WebDAV 文件名扫描统一支持 MP3、FLAC、WAV、M4A、AAC、OGG、OGA、Opus、WMA、AIF、AIFF、APE，扩展名不区分大小写，沿用现有解析方案提取歌名和歌手。扫描只建立文件名索引，不读取音频内容或内嵌标签；新增格式需重新扫描才会进入歌曲列表，现有扫描数量限制不变。

“在线播放”按扩展名选择音频/视频控件。音频复用已鉴权的 GET/HEAD `/api/v1/media-songs/{id}/video`（为兼容保留原路径），返回 audio/* 媒体类型，沿用 Range、后端 WebDAV 代理、凭据保护与关闭停止加载。音频失败可重试，切换或关闭时移除旧媒体元素。不提供转码，扩展名可入库不保证浏览器能解码；WMA、APE 等通常需要其他播放器。WebDAV 下载重定向限制不变。


## AI 配置

管理员专用 `/api/v1/ai` 提供厂商目录、多模型/搜索服务 CRUD、显式连接与结构化测试、默认搜索和功能绑定。密钥只写不读，服务与绑定使用 revision 并发保护，引用中的服务不能删除或停用。原分类配置接口保留兼容适配；边界与通用搜索响应见 [AI 设置](../../docs/ai-settings.md)，字段以 `singverse.openapi.json` 为准。

本地和 WebDAV 标签沿用歌曲 API 元数据结构，字段来源 kind=FILE 表示音视频内嵌标签优先值；读取通过以下短期授权回源协议执行。

计算服务使用 POST /internal/v1/media/remote-tags，所有本地／WebDAV 标签请求均携带 reference、callbackOrigin、filename、size；Go 提供短期授权 Range 回源。转码、音轨任务同样使用引用和回调地址，禁止传本地路径；原 /internal/v1/media/tags 已移除。连接测试增加 /internal/v1/callback/check 及 Go 的短期 /api/v1/internal/compute-callback/{id}。详见 compute-internal.openapi.json；前端不访问计算节点。

- 管理员性能节点：`GET/PUT /api/v1/compute-node` 和 `POST /api/v1/compute-node/test`；写入和测试要求 CSRF，凭据仅写，AUTO 解析部署变量、MANUAL 使用加密配置、DISABLED 停用。测试失败使用 Check.status 表达，不返回上游原始错误或触发管理员登出。
- 管理员局域网发现：`POST /api/v1/compute-node/discovery` 可省略请求体，或提交 `{network,port}`。使用 HTTP 单播，默认优先业务回源地址的私网 `/24`、已保存/部署节点地址及 Go 私网网卡，最多 3 个网段、768 个主机、32 个并发、单次请求 500 毫秒、总计 10 秒及 32 个结果；默认端口 18090、8090。显式 `network` 只接受 RFC1918 IPv4 `/24`～`/32`，本机回环仅允许 `/32`；显式 `port` 为单个 1～65535 端口。公网、链路本地及 IPv6 全网扫描均拒绝。返回短期 `id`、名称、固定 IP 端点、`nodeId`、`pairingAvailable` 和提示，发现记录仅在服务端保留 2 分钟。
- `POST /api/v1/compute-node/pair` 提交 `{discoveryId,revision}`，不接受客户端指定节点地址或凭据。Go 使用业务库持久 RSA3072 身份完成 PSS-SHA256 挑战签名及 OAEP-SHA256 凭据解密，再检查内部 Bearer 认证与节点到业务服务的回源；只有 `AVAILABLE` 才以 MANUAL 保存，失败保留原设置，修订号冲突返回 409。内部连接密钥、公钥、私钥、签名、挑战和 owner 身份均不进入浏览器。发现及配对均要求管理员 Cookie 和 CSRF；节点内网首次配对采用 TOFU，已绑定节点只允许相同身份恢复，且节点必须开启局域网配对。固定私网 IP、回环或 ULA 端点可配对，不跟随 HTTP 重定向。
- 计算内部 `GET /internal/v1/status` 验证 Bearer 凭据后返回身份和实际能力；与公开进程健康及兼容能力探测分离。

## TV 与亲友聚会

新增 TV/party 契约见 `singverse.openapi.json` 与 [TV 开发设计](../../docs/tv-development-design.md)。`/server-info` 提供稳定实例身份；电视先申请十分钟授权，管理员在 `/admin/#/system/tv-devices` 核对短码并批准。轮询秘密换取独立设备 token，重复领取返回同一凭据，数据库只存摘要。设备 token 不授予管理员权限；撤销设备后媒体及关联聚会访问立即失效。

`/tv/**` 和 `/party/**` 只接受 Authorization Bearer，不接受管理员 Cookie。这两个独立命名空间不使用 CSRF；管理端继续 Cookie + CSRF。亲友邀请预览使用 POST 读取以避免令牌进入 URL 访问日志，预览不加入；显式 join 获得十二小时参与凭据，昵称不决定权限。邀请有效一小时，刷新使旧邀请失效，散场失效全部参与授权。

聚会变更包含 requestId、expectedRevision；同主体同请求与载荷重试不重复入队，冲突 409 后先刷新。revision 是用户操作修订号，播放遥测有独立数据库 CAS 与 eventSeq，不让周期进度报告制造点歌冲突。NEXT 进入待确认交接，START 仅电视可执行；播放事件才使队列变为 PLAYING。电视进程重建调用 recover，失效旧播放实例、清理命令，将进行项放回待唱，不自动重播。

`POST /tv/sessions/{id}/playback-events` 可选 `durationMs`（1～86400000，原歌曲时间轴总时长，HLS 包含 offset），未知时省略。只接受当前 attempt 的递增 eventSeq；已确认且实际 `PLAYING`、有效进度大于零并进入最后 30 秒时，服务端在事务提交后预取首个 `WAITING` 队列项的 WebDAV 原片。同一播放实例、同一下一首只登记一次，不因每条进度重复创建预取，不触发转码或新增播放命令。TV 和扫码 H5 的队列排序、移除、清空以及 NEXT、STOP、END、recover、设备撤销会使旧预取引用失效；STOP 提交时撤销，无须等待电视回报 IDLE。旧响应、重复事件和预取失败不会改变队列或当前播放；手机单曲没有自动建立队列。 后台暂态失败总共最多尝试 3 次，前两次失败后分别等待 5 秒、10 秒；稳定的参数、权限、素材变化或容量错误停止。新前台请求优先 2 秒，之后前台媒体连接仍打开时预取限为 256KiB/s，前台连接结束后恢复常速；前台优先暂停不计失败。

原片缓存继续通过现有歌曲缓存管理接口查看，`target.mode=ORIGINAL`、`target.sourceBytes` 表示原文件与完整大小；可选 `cachedBytes/sourceBytes/complete` 区分已缓存字节和整曲完成，`complete=false` 仍需授权回源读取缺失范围，不能当作完整离线成品。

播放资源由 Go 按终端能力协商原片、离线兼容版本或实时 HLS。资源 READY 表示已取得授权引用，不保证目标电视能解码；原始媒体直接使用设备 Bearer 请求，并在每次 Range/HEAD 时重新检查撤销。歌曲与分类只读投影不暴露内部路径、来源凭据或模型配置。歌手入口按原始署名精确分组，不建立身份或别名归并。家庭规模保护上限：单场 500 个队列项、1000 项幂等操作、100 条待确认命令；超限明确拒绝。TV 需结束后开新场，不静默删除历史去重记录。

### TV 首页与当前歌曲详情

`GET /api/v1/tv/home` 仅设备可读，返回稳定服务身份 `server`、全库现存歌曲数 `totalSongs`、按扫描时间倒序/ID 升序的前 8 首 `recentSongs` 和 `generatedAt`。它不接收找歌筛选参数，不以推荐、热度或演唱历史名义包装扫描顺序。

`GET /api/v1/tv/library/songs/{id}` 读取当前歌曲展示投影，歌曲移除返回 404。投影沿用已应用元数据/文件标签与文件名解析的现有优先级，新增可用的 album、releaseDate、durationMs、scannedAt 与不透明 revision；无依据字段省略或 null，不补假值。revision 包含元数据和分类变化，供封面重载使用，不暴露路径或来源凭据。所有设备/参与者仍遵循现有授权；首页不对参与者开放。

TV 非转码增强新增设备专用 text-tracks 与 releases/current、releases/apk；直放资源含 revision。text-tracks 优先使用有效原始 LRC，缺少时复用已有内嵌歌词缓存；真实毫秒时间以 LRC 返回，无同步时间以新增的 `format=text` 返回，保留原有路径及鉴权，手机接口复用相同结果。读取不触发媒体重新探测。文字检索读取有效元数据，支持规范化全拼、首字母及精确编号；共享读索引最长约30秒更新，分类先过滤再分页。接口定义只覆盖实际读取/发布能力，不承诺转码或自动轨道语义。

聚会快照现同时返回 stateRevision（包含播放事件的持久版本）和 revision（队列乐观并发版本）；TV 用前者防止旧快照覆盖，用后者提交队列命令。换会话/NAS 的迟到响应被忽略。

分类封面新增管理员GET/PUT/DELETE /classification/tags/{id}/cover，以及Bearer只读 /tv/library/classification-tags/{id}/cover。首次上传version为空，覆盖/移除带当前coverVersion；内容SHA-256与标签revision独立。正式标签新增coverPath/coverVersion；停用标签对TV封面返回404，完整限制见OpenAPI。

歌手库按歌曲有效署名自动汇总并提供只读查询，歌曲订正通过现有审核应用接口保存 NAS 歌手覆盖，恢复接口需要 revision。歌手响应新增 automatic、fetchState、图片来源字段；ExternalTrack 新增可选 artistRefs，旧数据按空列表兼容。详见 OpenAPI。

共享歌单新增 `/api/v1/playlists` 管理接口、`/generate` 建议预览及 TV 只读歌单列表/详情/歌曲接口。创建需稳定 requestId，编辑与删除需 revision；生成返回候选范围和草稿，不自动保存。管理与 TV 封面路径分别使用相应身份鉴权。

## 转码与播放协商

新增 `/api/v1/transcoding` 管理端接口、`/api/v1/tv/transcoding` 设备会话资源和 `/api/v1/internal/transcode-input/{id}` 计算节点专用 Range 读取。客户端能力为运行时报告，实时返回 DIRECT/VERSION/HLS 与原媒体秒偏移；HLS 准备中需轮询会话并续租。所有播放资源仍经过 Go 授权，内部执行接口只对 Go 开放。离线任务持久化在业务库，成功文件由 Go 校验长度/SHA-256 后原子保存。详细行为、错误和部署配置见[转码说明](../../docs/transcoding.md)。

硬件加速配置：GET/POST `/api/v1/transcoding/hardware-settings`，POST `/hardware-settings/test`。保存携带 revision；冲突返回 409，指定硬件短样本失败返回 422。`backend=deployment` 恢复节点部署环境变量，草稿测试不切换当前后端。内部接口路径对应 `/internal/v1/transcoding/hardware-settings`，继续使用 Bearer 和请求体边界。

流水线 v2：播放执行携带 `pipeline`（实际硬解/零拷贝/GPU 色调映射、DV 和回退原因）及 `sampledPeakRssBytes`（0 表示未知）。客户端能力增加 `hdr_formats`、`dovi_profiles`、`dovi_compatibility`，默认不支持 DV；HDR 与 DV 能力分别协商。

`PlaybackClient.profiles.h264`／`levels.h264` 必须来自同一实际解码器能力组合，level 使用 ffprobe 整数单位（31=3.1、9=1b），不可只因 MIME 存在就固定报告 High/41。`quality=auto` 尊重已报告尺寸、帧率和码率；显式画质仍施加所选上限。`force=true` 在新执行中强制兼容视频编码及 AAC，不复用旧离线版本；已生成的匹配兼容缓存仍可直接使用。明确上报 H.264 profile/level 时服务端会限制编码参数；未报告时保持原默认值。公开请求字段不变，Go 到 compute 的内部 `target.profile/level` 与 `/negotiate.fingerprint` 见 compute README。HLS 只有主列表声明的所有 rendition 的首片和初始化文件均可读时才 READY，完成后的整曲缓存复制在后台执行。

手机原生只读曲库：GET `/api/v1/mobile/library/songs`、`/{id}` 和 `/{id}/text-tracks`，使用管理员 Cookie，不接受 TV/party Bearer 替代登录。列表复用既有搜索及分类，返回 MobileSongPage；条目保留独立媒体 ID，补充格式/媒体类型，封面指向管理员资源。播放继续使用 `/api/v1/transcoding`；手机本机收藏和历史不属于服务端业务表。

## 全曲库音轨角色与离线分离

新增管理员 `/media-songs/{song}/audio-roles` GET/PUT、`/audio-jobs` POST、`/audio-jobs/{id}/cancel` POST 和独立伴奏 GET/HEAD。角色包含 state、origin、revision、originalId、backingId；纠正只接受两条本地探测存在的源音轨，旧 revision 返回 409，素材指纹变化使旧角色/伴奏失效。亲友及 TV 的 `/api/v1/{party|tv}/audio-roles/songs/{song}` GET/PUT 使用活跃聚会 Bearer，全员可纠正，分离仍限管理员。管理员 POST/PUT 沿用 CSRF。

播放 `PlaybackOptions.audioRole` 按共享角色选用；`PlaybackResource.audioRoles` 为首次协商快照，`backingUrl` 为 Go 授权的独立伴奏，客户端合并时保留视频原时间轴并处理 offset。`/api/v1/internal/audio-input/{id}` 只允许关联计算节点凭据读取 RUNNING 任务的当前素材。

compute 内部 `/internal/v1/audio-roles/tasks` 接收固定 UUID、mode=PROBE|ANALYZE|SEPARATE 和已授权输入，支持查询、cancel、artifact、release。PROBE 无需模型，ANALYZE/SEPARATE 仅在依赖与本地模型摘要均符合固定版本时声明能力。Go 持久化队列，compute 只执行、保存有限生命周期的执行记录和临时文件；节点重启的未完成执行标为 FAILED，不冒充完成。详见 compute README 的资源预算、模型安装及验证边界。

`/api/v1/audio-processing` 为管理员伴奏中心：歌曲分页筛选、持久批次预览/提交/详情/取消/重试、单项取消、当前节点及模型下载/导入/试跑/选择。变更请求继续使用管理员会话和CSRF；上传按固定 `model` 参数接受有界原始权重或多文件模型 ZIP，省略参数兼容 htdemucs。基准上限 100MiB，htdemucs-openvino 上限 128MiB，未知模型及超限拒绝。compute内部 `/internal/v1/audio-roles/models` 使用Bearer鉴权，模型下载仅使用固定清单；原有单曲音轨接口保留。

节点响应保留原 `model` 字段，并增加 `models`、`build` 和 `deployment`；后端支持 `openvino`，当前镜像未包含时返回 `IMAGE_MISSING`。可用资格绑定构建、运行库、设备与模型摘要；变更后须重新试跑。镜像构建及模型安装不等于硬件通过验收。

## 电视首页个性化

新增`/api/v1/admin/tv-settings`及封面上传/删除/图片接口：管理员Cookie读取，写入再验证CSRF与全局revision；上限8张JPEG/PNG，单张2,000,000字节、4096像素，归一为最长边1280px的JPEG。上传即保存并启用；PUT完整保存文案、轮播设置与全部已有图片的启停/顺序，不能通过PUT增删图片。冲突409，不静默覆盖。

`GET /api/v1/tv/home`兼容增加`appearance`与`featuredPlaylists`，后者最多4个已保存共享歌单；配置按数组顺序提供已启用图片。`GET /api/v1/tv/home/banners/{id}/image`只接受已授权device，不接受亲友participant；删除或禁用404。全部图片接口no-store，元数据含稳定内容版本，TV可自行有界缓存。没有公开静态图片路径或外部URL代理。


## 歌曲缓存（HLS 与原片）

管理员 `GET /api/v1/transcoding/cache` 按 `page`（默认0，上限1000000）、`size`（默认20，上限100）分页查看 Go 服务节点完整成功的实时 HLS 与已发布完整块的 WebDAV 原片缓存，按 `lastAccessAt` 倒序和 ID 升序。返回设置、Go 缓存目录实际用量、缓存条目总数，以及包含缓存 ID、可用歌曲名称/歌手、音频/视频类型（`mediaType=VIDEO|AUDIO`）、已缓存大小、生成/访问时间、复用次数、租约状态和输出参数的条目；不暴露来源路径与节点凭据。`target.mode=ORIGINAL` 的条目还含 `cachedBytes/sourceBytes/complete`；部分原片不会标为整曲完成，缺失范围仍受权回源。可选 `residueBytes` 表示损坏或待清理文件的实际磁盘占用，计入总 `usageBytes`，但不计入有效 `bytes/cachedBytes` 或完整度。实际用量可包含接收中的临时文件；列表不包含独立保存的离线兼容成品。

`PUT /api/v1/transcoding/cache/settings` 保存共用容量（整数 bytes，64MiB–1PiB）、`HYBRID|MANUAL`、兼容字段 `ttlHours` 和 `revision`；可选整数百分比 `highWatermarkPercent/lowWatermarkPercent` 默认 90/75，要求 `1≤low<high≤100`。旧四字段请求仍有效，省略水位保留已存值，未知字段返回400、旧修订返回409。已有 LRU/LFU/TTL 自动迁移为 HYBRID 并增加修订，旧策略写入作为 HYBRID 别名兼容；MANUAL 保留，`ttlHours` 仅保留往返兼容，不再控制到期删除。部署容量只负责首次初始化，后续以数据库设置为准；磁盘最低保留空间仍独立生效。

混合分数为 `S=0.4H+0.4R+0.2F`：H 每次有效播放增加0.05并限幅至1后按14天半衰期衰减；普通递增事件以O(1)聚合，晚到有效事件仅对同歌按发生时间顺序重折叠以保留限幅语义。R 从最近实际播放活动起按7天半衰期衰减，`F=min(1,ln(1+近7×24h有效次数)/ln(21))`。只有单次实例达到30秒真实播放或短曲完整结束才增加一次 H/F；管理员试听只更新活动保护与 R，不计热门。预取、Range、下载、定位和租约续期不增加热度。3天软保留通过可衰减加分进入 `priority`，不是不可回收的硬保护。生命周期 HOT 为近7天且至少3次有效播放，WARM 为未满30天，COLD 为30–60天，EVICT 为60天及以上；未播放条目以创建时间分层。列表增加 `H/R/F/score/priority/lifecycle/playCount7d/lastPlayedAt`、`pinned/pinRevision/protectionReasons` 和 `state=ACTIVE|DELETING`，可选 `usageReady/statisticsReady/committedBytes/reservedBytes/cleanupGeneration` 区分准备中和真实零值。

`GET /cache/pins?page=0&size=20` 分页列出歌曲级固定，包括暂无缓存或已下架的记录；`PUT /cache/songs/{songId}/pin` 提交 `{pinned,revision}`，返回 `{pinned,revision}`。固定覆盖同曲现有及未来所有原片/HLS变体，不强制下载整曲、不变成离线成品；取消固定不删除文件。批量清理与自动回收都保护播放、下载、固定及近24小时实际播放。24小时从最近实际播放活动计算，继续实际播放会刷新，暂停、缓冲和预取不刷新。单份删除须先取消固定；确认后可仅覆盖该份的近期保护，始终不能覆盖活动播放/下载保护。

新管理页使用异步 `POST /cache/cleanup-jobs`（202，`{mode:POLICY|CLEAR|DELETE,entryId?,confirmRecent?}`），POLICY 回收到低水位，CLEAR 清理全部未保护缓存，DELETE 指定单份。`GET /cache/cleanup-jobs/{id}` 查询，`POST /cache/cleanup-jobs/{id}/cancel` 请求取消；QUEUED/RUNNING/CANCELLING 为非终态，SUCCEEDED/PARTIAL/BLOCKED/FAILED/CANCELLED/INTERRUPTED 为终态。结果保留 `removed/skipped/failed/freedBytes/usageBeforeBytes/usageAfterBytes/targetBytes/targetReached/spaceInsufficient/shortfallBytes/skippedByReason/errors`，删除失败保留索引，已完成删除不会因取消而回滚。未达75%目标与实际空间不足分开表达；保护条目使目标未达时不一定阻止继续缓存。错误不包含路径或凭据。旧 `POST /cache/clear` 同步返回 removed/skipped，旧 `DELETE /cache/{id}` 仍返回204，近期覆盖需 `?confirmRecent=true`。全部写入要求管理员 Cookie 与 `X-CSRF-Token`，设备/亲友 Bearer 不能管理缓存。

实时 HLS 按歌曲、素材指纹及完整播放参数查找缓存，命中时沿用同源受权播放接口，创建独立播放会话，执行结果包含 `cacheHit=true` 和不透明 `cacheId`。缓存读取、管理及清理不依赖 compute 在线。未命中时 compute 执行解码，Go 下载已完成产物并验证长度、SHA-256 和整包 HLS 引用，原子发布后才进入索引；确认落盘后释放 compute 临时产物。任一策略和批量清理都跳过活动租约，满额且无法腾出空间时返回507，删除失败保留索引。重启保留完整缓存，会话租约按续租到期释放。

`PlaybackOptions.start` 是原媒体时间轴上的请求起点。非零起点优先匹配仅将 `start` 设为 0 的同参数整曲缓存；命中后 `PlaybackResource.offset` 仍为缓存的真实偏移 0，客户端对 HLS、DIRECT、VERSION 均按 `max(0, start - offset)` 定位本地媒体时间，播放回报使用 `currentTime + offset`。无完整缓存时仍精确匹配局部起点或按请求起点转码，画质、编码、音轨、字幕、终端能力及兼容处理意图保持隔离；不会因起点不同主动删除旧缓存。

## 播放记录与缺歌反馈

`GET /api/v1/playback-history`、`/popular`、`/summary` 共用 `q`（最多 200 字符）、`period=7d|30d|all`（默认 30d）、`songId`（最多 64 字符）过滤。时间范围以服务端 UTC 当前时刻向前滚动，全部时间也排除未来记录。明细和排行默认 `page=0,size=20`，最大页大小 100，page 上限 1000000；明细按开始时间倒序、ID 倒序，排行按有效次数倒序、最近播放时间倒序、songId 升序。有效播放为实播至少 30000ms，或时长为 1～29999ms 的短歌曲自然结束且实播不少于歌曲时长；拖动进度不等于实播时长。后台试听 `ADMIN` 的 effective 固定为 false，并排除在排行所有字段之外。排行仅返回有效次数大于零的歌曲，汇总的有效次数和歌曲数也排除后台试听；总尝试数和实播总时长则包含全部匹配明细。歌曲与设备展示使用历史快照，不依赖曲库歌曲继续存在。

`GET/POST /api/v1/missing-song-feedback` 查询/登记缺歌，`GET/PUT /{id}` 读取/处理反馈。列表支持 `q`、`status=ALL|PENDING|PROCESSING|RESOLVED|CLOSED`，默认全部状态，分页默认 0/20、最大 100，搜索与状态在 SQL 分页前筛选，按登记时间倒序及 ID 升序。创建必须提供稳定 UUID requestId 和全部原始反馈字段；相同 ID、相同原始输入重试返回原记录（201），不同输入返回 409。处理只更新 status、handlingNote、resolvedSongId，并校验 revision；RESOLVED 必须关联现存歌曲，重开清除关联和 resolvedAt。反馈没有删除接口，也不会自动下载歌曲。

播放采集新增管理员 `POST /api/v1/playback-attempts` 与手机 `POST /api/v1/mobile/playback-attempts`，提交 `{songId,deviceName?,startedAt?}`，201返回服务端签发的 `attemptId/writeToken/eventSeq=-1/leaseSeconds=120/startedAt/serverTime`；对应 `/{attemptId}/events` 提交 `{writeToken,eventSeq,state,actualPlayedMs,elapsedMs,durationMs?,occurredAt?,lastActivityAt?}`。日期均为 UTC epoch 毫秒：起点最多回溯24小时，occurredAt与起点+elapsed误差≤5秒，未来容忍5秒；新客户端在采样时冻结日期，注册重试保留原起点和已累计实播，排队/重试不得改写成发送时刻。R、近期保护按实际lastActivityAt，租约按occurredAt+120秒，迟到上报不会刷新保护。两组接口均使用管理员 Cookie 与 CSRF，路由固定 ADMIN/MOBILE，凭据绑定当前登录会话，不能由请求伪造终端或其他用户实例。TV 复用当前聚会实例及已有 playback-events，新增可选成对的 `actualPlayedMs/elapsedMs` 和 `startedAt/occurredAt/lastActivityAt`，起点首次绑定后不能改变；旧客户端缺少时钟时不从 positionMs 推算实播。亲友不能代报 TV 播放。TV 接到确认LOAD即绑定时钟并在准备期每15秒发送实播为0的PREPARING心跳；就绪不重置。过期后仅当前实例ENDED/IDLE可同步队列终态，历史保持INTERRUPTED且不续租或补计热度。

采集只依据主媒体真实播放墙钟；定位、暂停、缓冲不增加 actual，计数单调且增量不得超过 elapsed 增量+1秒，elapsed 不得超过服务端经过时长+5秒。实际播放活动更新最近播放和24小时保护；暂停心跳只续活动租约。管理员主预览和独立原始音轨试听不增加 H/F，独立伴奏不重复建实例。同歌更换轨道、编码或资源继续同一逻辑实例，关闭/失败报告结束状态；普通 ERROR 可在最后一次事件发生后的120秒窗口内由同实例恢复，过期返回410；STOPPED/COMPLETED/SKIPPED 永久结束。120秒无活动到期返回410，客户端停止旧实例采集，下一次明确播放再创建；采集失败不阻断媒体播放。旧聚会队列不转换成听唱历史。

查询与缺歌接口仅接受管理员 Cookie，缺歌写入另需 `X-CSRF-Token`；字段见 `PlaybackHistory*`、`PlaybackPopular*`、`PlaybackAttempt*`、`PlaybackActivity*`、`MissingSongFeedback*` schemas。详见[功能说明](../song-activity.md)。

## 大曲库伴奏索引与后台准备

`GET /api/v1/audio-processing/songs` 增加 `generation` 和 `building`，首次索引未就绪返回 503 及 `Retry-After: 1`；已有快照重建期间仍可读取。筛选后的当前页才加载完整详情。

`POST /api/v1/audio-processing/batches/preview` 返回 202，批次先处于 `PREPARING`，包含 `selectionTotal`、`prepared` 和 `error`。通过原详情接口轮询，完成后为 `PREVIEW`，此时才能提交；准备中提交返回 409。准备中可调用原取消接口，重启时未完成准备转为 `FAILED`。所选歌曲及指纹在创建时固定，每 200 条短事务写入，执行前继续核对当前指纹；数据库采用新增迁移，原有批次保留。

已删除只返回 410 的旧歌手创建、编辑、绑定和 credits 接口。自动歌手库、图片管理、分类及歌曲歌手订正仍使用现有实际接口。旧 `ComputeNodeClient` 及未注册的路径标签接口已删除；连接和回源验证覆盖真实 `ComputeNodeProbe` 链路。

## 后台任务中心

管理员只读 `GET /api/v1/background-tasks`、`/{id}`、`/{id}/children`、`/{id}/items` 查询 Go 持久化快照，复用 Cookie 会话；不触发计算节点或媒体源请求。列表默认 `view=ACTIVE`，支持 `HISTORY/ALL`、类型、状态、名称/编号搜索和 0 起始分页（默认 20、最大 100）。首页只列操作批次，按运行、取消、排队、暂停、等待开始、终态在分页前排序；关键词匹配关联歌曲和子任务，按批次去重。详情分开返回批次状态与 operation 原业务阶段；children 支持类型/状态筛选并运行优先，detailMode 指示使用 children 或已有 items，避免重复。扫描后探测到快速识别的范围全部结束才结算整批；单曲是含一项的批次。明细按原状态筛选，counts 返回未筛选的统计。整批终态保留 30 天，未完成及有效批次引用的共享任务持续保留。未知分母的百分比为 null，缺失的历史时间为 null，帧计数只代表当前阶段。详情包含业务页入口与关联有效性；所有返回字段均为展示白名单。schema 为 `BackgroundTask*`，完整行为见[后台任务中心](../../contracts/background-tasks.md)。

## 快速伴奏配方与版本接口

SEPARATE 与批量请求新增可选 separationProfile（DSP_FAST/AI_CLEAN/LEGACY_DEMUCS）、modelId、outputFormat、preview、backend；FAST 仍表示音轨识别。sourceAudioId 指定当前已探测音轨或 LEFT/RIGHT 声道时必须 sourceConfirmed=true。GET audio-roles 增加 versions，POST /api/v1/media-songs/{song}/accompaniment/{id}/activate 仅接受当前指纹下完整 AAC；下载支持 audio/flac。新字段只向 separation.version>=1 的节点发送，默认旧任务保持旧协议。真实行为、依赖和验收边界见 [伴奏说明](../../services/singverse-compute/AUDIO-SEPARATION.md)。

## 歌手封面批次与类型整理

`GET /api/v1/artists` 新增可选 `artwork=MISSING|REVIEW`，与名称、类型、歌曲数筛选一起在分页前应用，旧参数继续兼容。`GET /artists/artwork-platforms` 只返回已启用的平台标识；网易云/QQ/酷狗沿用元数据平台设置，酷我（`KUWO`）、汽水（`QISHUI`）、TheAudioDB 是无需账号 Cookie 的公开歌手图片源。汽水的长数字 ID 不经浮点转换；同名搜索结果必须继续核对歌曲或进入候选确认。

`POST /artists/artwork-batches` 保存 1～1000 位显式选择或全部筛选结果快照、图片平台和幂等 requestId；相同输入重放返回原批次，不同输入同标识返回 409。`GET /artists/artwork-batches/latest` 无批次时返回 null，`GET /artists/artwork-batches/{batch}` 按提交时稳定顺序分页读取真实结果，默认 20、最多 100；批次处理头像封面，已有图片/手动移除均保留。显式平台不回退到其他图片源，候选继续明确确认后才使用；确认后同步批次状态。离页和断线不取消后台执行。任务中心新增 `ARTIST_ARTWORK`，进度单位 `ARTISTS`，深链接回到该歌手批次。

`POST /artists/categories` 按每位歌手 id/revision 原子确认 MALE、FEMALE、GROUP 或 UNKNOWN，任一版本冲突整批回滚；不自动猜测性别。所有新增接口均位于 `/api/v1` 下，仅管理员 Cookie 可访问，写入继续受同源与 CSRF 保护；不改变 TV/亲友 Bearer 边界。

Nginx 为上述两个批量写入接口单独允许 128 KiB 请求，以容纳 1000 位歌手；其他 JSON 请求默认上限不变，Go 仍逐项校验范围、标识与修订号。

歌曲元数据平台现为 `NETEASE|QQ|KUGOU|KUWO|QISHUI`，设置与搜索最多 5 个，默认配置不变。候选封面 GET 可传 `quality=ORIGINAL|HIGH`，单曲确认可传 `coverQuality`，均默认原图；服务端只从已有候选推导 CDN 版本，确认结果保存所选版本，不接收外部 URL。平台原图不再经过 2 MB / 4096 像素门槛，保留源字节，网络与缓存读取上限 32 MiB、20 秒；上传限制保持独立。图片加载失败与平台未提供封面分别处理。


## 媒体资料导入与来源入库

来源创建可选 `startScan` 和 WebDAV 的 `autoExtractEmbedded`；省略时保持旧客户端只保存的行为。后台导入使用 `embedded-metadata/import-jobs` 上传、预览、映射、确认、查询、取消与重试，返回 lower camelCase 的 `MediaImportJob`；上传完成后由服务端续跑。`rebind` 保留歌曲身份与本地订正，单独重新核验媒体。共享 v3 稳定系列和版本支持已有歌曲合并，v2 仍可导入；默认完整 ZIP，增量要求匹配基线，旧版本拒绝，删除提示不自动应用。详细状态、路径规则、限额及兼容边界见[媒体库说明](../../docs/media-library.md#媒体入库资料合并与分享2026-11-10)。
