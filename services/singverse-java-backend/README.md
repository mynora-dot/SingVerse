# SingVerse Java 后端

Java21、Spring Boot3.5.16、Maven Wrapper3.9.16。提供启动、配置、Actuator、MyBatis XML 数据访问与数据库连接检查、亲友H5欢迎入口及NAS管理前端静态托管；新增可选的Python计算节点内部能力探测客户端；已增加单管理员账号、会话、TOTP MFA及SQL迁移；已支持管理端原始音视频在线播放，已新增 TV 授权、聚会队列与亲友点歌；已增加 compute 实时 HLS 与离线兼容版本编排，详见[转码说明](../../docs/transcoding.md)。

执行下列Maven命令前，从仓库根目录运行`make frontend-build dbmate-setup`（需要Node.js 22.19+和npm），然后进入本目录。Maven缺少管理产物会明确失败，避免打出缺失界面的JAR；修改前端后必须重建。根`make java-backend-test`、`make web-check`与`make backend-check`自动执行这一前置步骤。

```sh
./mvnw -B verify
# 先从仓库根执行make compute-setup，再验证真实Python进程联动
./mvnw -B -Pbackend-check verify
./mvnw spring-boot:run
curl --fail http://localhost:8080/party/
curl --fail http://localhost:8080/admin/
curl --fail http://localhost:8080/api/health
./mvnw -B -Dtest=WebWelcomeTest,AdminWebTest test
./mvnw -B -Dtest=SQLiteConnectionTest test
# 先启动MySQL并导出MYSQL_*变量
./mvnw -B -Pmysql-check verify
SPRING_PROFILES_ACTIVE=mysql ./mvnw spring-boot:run
```

主服务工程、Maven制品和Spring应用名均为`singverse-java-backend`；Java包名保持`com.mynora.singverse`。

亲友H5和NAS管理页面统一在兄弟工程`../singverse-frontend`中开发。Vite一次构建`/party/`与`/admin/`两入口，Maven将整个`dist`复制到`target/classes/static`，不混入Java源码资源目录。Spring静态资源托管JS/CSS，`AdminWebConfiguration`只处理管理目录首页与尾斜杠。运行`java -jar target/singverse-java-backend-0.1.0-SNAPSHOT.jar`后提供两个入口；运行时不需要Node，无CDN、微信JS-SDK或小程序AppID。根`make web-check`执行真实访问集成测试。

管理端有概览、系统状态、登录和系统参数，初始admin/admin，支持改密及可选MFA；详情见[认证说明](../../docs/authentication.md)。不直连Python或数据库。其静态产物未来可由Go复用，详见[管理端说明](../../services/singverse-frontend/README.md)与[前端托管契约](../../contracts/frontend-hosting.md)。

后续微信扫码的二维码应是手机可达的NAS URL。当前未生成二维码、未验证微信内置浏览器实机，不提供点歌/会话接口。API/WS建议与网页同源，不公开数据库给手机。

默认SQLite文件`./data/singverse.db`相对进程工作目录；SQLITE_PATH覆盖并自动建父目录，单连接池启动SELECT 1。mysql Profile通过MYSQL_HOST/PORT/DATABASE/USER/PASSWORD配置，密码必填，不回退SQLite。Spring不自动读取.env。

业务授权、扫描编排与唯一业务数据库写入由 Java 负责，FFmpeg 等媒体处理由独立 Python 服务执行。管理员 `/api/v1/compute-node` 读取/保存单个活动节点配置，`POST /api/v1/compute-node/test` 测试草稿而不保存。AUTO 从部署的 `COMPUTE_NODE_URL`、`COMPUTE_AUTH_TOKEN_FILE` 读取连接；MANUAL 使用数据库加密凭据与路径映射；DISABLED 停止新调用。节点不属于 Actuator 健康依赖，不在 Java 启动时连通检查。测试请求 2 秒连接、4 秒响应超时、64 KiB 响应上限，保持 TLS 验证且不跟随重定向；HTTP 限本机/受限内网，禁止链路本地等地址。接口只返回 hasToken；更改地址须重填或明确清除凭据。SQLite/MySQL 配置迁移与 MyBatis XML 同步。每次扫描固定连接快照，后续修改只影响新扫描。`make backend-check` 验证真实 Python 生命周期及主服务健康隔离。

mysql-check是Maven测试Profile，执行MySqlConnectionIT；普通测试独立验证SQLite及H5，临时数据库不污染开发文件。健康端点保留Actuator原生响应，不泄露数据库详情。

## Nginx与API前缀

[nginx/nginx.conf](nginx/nginx.conf)用于Nginx部署和Docker打包：对外管理入口为根路径，点歌H5为 `/party/`，所有 `/api` 命名空间请求原路径转发到后端。当前健康端点为 `/api/health`，后续业务使用 `/api/v1`；健康响应保持Actuator原生格式。静态产物复制、Docker内部服务名和部署检查见[Nginx说明](nginx/README.md)。已提供[联合Dockerfile](Dockerfile)与[应用Compose](../../compose.app.yaml)，从仓库根执行 `make docker-up` 后访问 http://localhost:18080/ 。SQLite保存在独立命名卷；运行和检查见[Docker说明](../../docs/docker.md)。NAS实机尚未验收。

## 媒体来源配置

新增受管理员会话和 CSRF 保护的 `/api/v1/media-sources` CRUD。本地目录保存时检查服务可见绝对路径及目录读取；WebDAV 仅校验配置并加密保存密码，不发起远端请求。新增 SQLite/MySQL dbmate 迁移，密码复用主密钥并使用独立 AAD。主密钥备份现在也涉及媒体源凭据；详见[媒体库管理](../../docs/media-library.md)。现已支持本地目录文件名扫描及索引保存；WebDAV 已支持十万首级后台文件名扫描，媒体处理任务尚未实现。

## 包组织与依赖约定

采用单体内的业务模块分包，根包 `com.mynora.singverse` 只保留 Spring Boot 入口，组件扫描继续覆盖全部子包。

```text
com.mynora.singverse/
  SingVerseApplication
  auth/
    controller/       账号与 MFA HTTP 入口
    dto/              认证请求结构
    service/          登录、会话、MFA 与改密用例
    repository/       账号初始化、Mapper 接口与审计
    security/         Cookie、来源检查、CSRF 与 SecurityFilterChain
    crypto/           既有主密钥与凭据加密（媒体源复用独立 AAD）
    recovery/         本机终端恢复
    exception/        认证业务异常
  media/
    controller/       媒体来源 HTTP 入口与异常响应
    dto/              媒体来源请求、响应与分页结构
    service/          目录、WebDAV 和凭据更新规则
    repository/       Mapper 接口、行映射与唯一冲突处理
    filename/         独立、无 IO 的 KTV 文件名解析器
    model/            版本化配置文档、共享定义与方案引用
  infrastructure/
    database/         数据源、连接检查、dbmate 迁移和进程锁
    compute/          Python 内部能力探测客户端
    web/              管理与亲友入口静态托管
```

媒体来源按 `controller → service → repository` 调用，控制器不处理 SQL、路径校验或加密。DTO 只描述 HTTP 数据，不暴露数据库行或密码。媒体仓储使用自己的 JdbcTemplate，不通过账号仓储读写媒体表；显式依赖 `DatabaseInitializer` 保证先完成迁移。数据库锁随该基础设施 Bean 的销毁释放；账号初始化和本机恢复继续归认证模块。

认证模块本次保留既有事务、SQL 与安全行为，`AuthStore` 封装存储实例，以方法访问支持 Spring 仓储代理；未将现有认证逻辑改写为新的领域模型。公共加密仍由既有认证主密钥管理。新增功能应落入相应业务模块，不把业务类堆进根包或基础设施包，也不预建空接口、空模块或独立 Maven 子工程。

测试继续使用 `src/test/java`，需要访问包级构造器的基础设施单元测试跟随对应包；HTTP 集成测试位于根包下并使用隔离数据和随机端口。接口、配置、迁移 SQL、JAR 名称及构建入口不因 Java 包移动而改变。

## KTV 媒体文件名解析器

`com.mynora.singverse.media.filename` 是 Java 21 纯逻辑模块，不需要启动 Spring，也不使用网络、文件系统、数据库或大模型。输入仅为文件名字符串；调用方负责提供文件名，解析器不会从路径或 URL 提取 basename。斜杠可以保留为名称中的字符。

```java
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.filename.FilenameParseResult;

var parser = new KtvFilenameParser();
FilenameParseResult result = parser.parse("周杰伦-晴天-国语-原唱.mkv");
// title: 晴天; artists: [周杰伦]; language: MANDARIN; versionType: ORIGINAL
// confidence: 0.85; parseStatus: SUCCESS; parserRule: ARTIST_TITLE_TAGGED
```

入口无可变状态，可由多个线程复用；结果 record 及其列表均不可变。它只推断文件名含义，不验证音视频实际内容。独立内核不依赖 HTTP 或媒体来源服务；规则配置和预览 API 作为单独管理功能提供，本地扫描服务通过已保存方案快照复用内核，内核自身不访问文件系统或数据库。

### 结果字段

| 字段 | 含义 |
|---|---|
| `originalFilename` / `normalizedFilename` | 完整原始输入 / 统一字符宽度、空白、常见横线和括号、扩展名大小写的副本；标准化名称仍包含标签 |
| `title` / `artists` | 歌名 / 歌手列表；不能确定时为 null / 空列表 |
| `language` / `versionType` | 语种 / 版本枚举，默认 `UNKNOWN` |
| `songCode` / `extension` | 保留前导零的字符串编号 / 小写、无点扩展名；缺失时为 null |
| `qualityTags` | 去重后的规范清晰度标签，保留多个不同标签 |
| `confidence` / `parseStatus` | 0～0.99 的歌手/歌名结构分数 / 核心解析状态，不是统计正确率 |
| `parserRule` / `unparsedTokens` | 最终规则标识 / 未确定片段，保留重复片段与冲突标签 |
| `needsReview` / `diagnostics` | 是否复核核心字段 / 包含原因和分数增减值的诊断列表（附加标签可仅作提示） |

语种枚举为 `MANDARIN`、`CANTONESE`、`ENGLISH`、`JAPANESE`、`KOREAN`、`OTHER`、`UNKNOWN`；词典支持国语/普通话/Mandarin、粤语/广东话/Cantonese、英语/英文/English、日语/Japanese、韩语/韩文/Korean、其他/未知。英文匹配不区分大小写。

版本为 `ORIGINAL`（原唱/原版）、`ACCOMPANIMENT`（伴奏/纯伴奏）、`INSTRUMENTAL`（纯音乐）、`DUET`（合唱/对唱）、`KARAOKE`（卡拉 OK/卡拉OK/KTV）、`UNKNOWN`。清晰度支持 480P、720P、1080P、2K、4K、UHD、HD；扩展名支持 MKV、MP4、MPG、MPEG、AVI、TS、VOB、MOV、WMV，大小写不敏感。

### 规则与边界

| 格式 | 示例 | 规则 |
|---|---|---|
| 歌手《歌名》 | `周杰伦《晴天》.mkv` | `ARTIST_BOOK_TITLE` |
| 歌名（歌手） | `晴天（周杰伦）.mkv` | `TITLE_PAREN_ARTIST` |
| 编号 歌手 歌名 | `00128 周杰伦 晴天.mpg` | `CODE_ARTIST_TITLE` |
| 歌名_歌手及尾部标签 | `晴天_周杰伦_伴奏.mp4` | `TITLE_ARTIST_UNDERSCORE` |
| 歌手-歌名及尾部标签 | `周杰伦-晴天-国语-原唱-1080P.mkv` | `ARTIST_TITLE_TAGGED` |
| 歌手-歌名 | `周杰伦-晴天.mkv` | `ARTIST_TITLE` |
| 仅歌名 | `晴天.mkv` | `TITLE_ONLY` |

标准化和标签提取先执行，因而同样支持 `陈奕迅 - 富士山下 [粤语].mkv`、`周杰伦&温岚-屋顶-国语.mkv` 和 `周杰伦 － 晴天 （国语） [原唱].mkv`。明确的书名号/尾括号结构优先，分隔格式其次，仅歌名是后备规则；同优先级规则存在不同解释时，仅保留一致的核心字段。

- 标签按完整括号内容或尾部完整分隔片段匹配，不从名称内部搜索子串。尾部标签须有剩余歌曲结构；`周杰伦-原唱.mkv` 中的“原唱”保留为歌名。书名号内部的括号内容保留为歌名的一部分。
- 未知方括号标签、已有歌手/歌名结构后的未知括号内容，以及明确的未知标签提示都保留为诊断信息，不影响核心字段成功判定。`MTV`、`消伴`、`风景`、`人物`、`演唱会`、`流行` 等只作为未识别证据，不生成新版本或曲风字段。`无原唱` 不推断成伴奏，`消伴` 也不推断成纯伴奏。
- 同类别互相冲突的标签返回 `UNKNOWN`，冲突内容全部保留，但不要求复核已明确的歌手/歌名；同义标签重复不视为冲突。单独出现的“未知”作为语种未知标签处理。
- 只有已确认歌手字段中的 `&`、`＆`、`、`，以及空白边界完整且两侧非空的 `feat.`/`ft.` 才拆分；歌名中的合作符号、歌手名称中的斜杠和普通空格保留。已确定歌手字段且有明确合唱/对唱标签时，可拆分以空格分隔的 2～4 汉字姓名；包含乐队、乐团、合唱团、组合、战队、乐坊等团体后缀时保留整体。其他带空格的署名保留完整文本，如 `My Band`、`TFBOYS 韩庚`，通过 `ARTIST_CREDIT_PRESERVED` 提示尚未拆分成员，不再将整个歌手字段置空。此规则是署名规范化启发式，不能保证识别所有团体名。
- 有空格包围的横线优先作为结构分隔符，可保留 `AC-DC - Song-Part 2` 内部横线。多个无明确归属的片段返回 `AMBIGUOUS`，不强行拼出歌名。
- 曲库语言/标签尾部之后的副本标记和计数（如 `-国语-流行-2`、`-国语-流行_1`、`-国语-流行 - 副本-2`）保留为附加片段，不再阻塞核心字段。没有这种上下文的 `A-T-2` 仍视为结构歧义，不把歌名数字一律当副本编号删除。
- 编号只自动提取前导零数字前缀，或 `编号:`、`编号：`、`ID:` 标记后的数字；标记大小写不敏感。未标记的 `1984 周杰伦 晴天` 不确认编号；`1984.mkv`、`七里香2004.mkv` 保留为歌名。编号加空格的多词名称、编号加横线的正反顺序均可能有歧义，需要复核。
- 无扩展名只产生提示，歌手/歌名完整时仍可 `SUCCESS`；不支持的扩展名（包括 FLAC）、空输入、控制字符、孤立代理字符、不匹配括号及没有有效名称的输入返回 `FAILED`。仅标点不视为有效歌名。原始输入始终保留，不做文件访问。

没有歌手词典或调用方命名约定时，无法可靠识别所有反向命名、内部连接符、团体名和多人名单。普通两段横线按“歌手-歌名”、下划线按“歌名_歌手”的约定处理；`SUCCESS` 表示符合当前规则，不保证这些命名约定与媒体事实一致。

### 置信度与状态

当前以歌手和歌名为验收核心。结构基础分：书名号 0.95，尾括号及明确编号空格结构 0.90，横线/下划线 0.85，仅歌名 0.45。结构或歌手字段本身有歧义时减 0.25；语种、版本、清晰度、未知附加标签、标签冲突和缺扩展名均只记录诊断，分数增减为零，不影响核心字段判断。结果限制为 0～0.99，并保留各项诊断供调用方解释。

歌手和歌名完整、没有核心归属歧义、分数至少 0.80 时返回 `SUCCESS`，`needsReview=false`，即使 `unparsedTokens` 中仍有附加标签。核心顺序或分隔本身有歧义时返回 `NEEDS_REVIEW`；只有歌名等不完整核心字段时返回 `PARTIAL`；无有效输入/候选、非法名称或不支持的扩展名返回 `FAILED`，分数为零。缺失或互相冲突的语种/版本仍为 `UNKNOWN`，不推断为任何媒体事实。

### 文件、测试与扩展

本包新增 11 个生产文件：`KtvFilenameParser`（入口）、`FilenameParseResult`（record 与嵌套枚举）、`FilenameNormalizer`、`ExtensionHandler`、`TagDictionary`、`TagExtractor`、`SongCodeExtractor`、`FilenameRule`、`FilenameRules`、`ArtistParser`、`ConfidenceCalculator`。只有入口与结果类型对外公开，其余实现保持包级可见。

测试位于同包的 `src/test/java`：`FilenameSamples.java` 集中保存各命名规则的字段预期，`KtvFilenameParserTest.java` 验证别名、扩展名、歧义、完整未知片段、评分、不可变性、并发复用和异常输入。测试直接 new 解析器，不启动 Spring，也不访问 SMB 或数据库。

```sh
# 从仓库根执行现有产物前置步骤；无需启动主服务
make frontend-build
cd services/singverse-java-backend
./mvnw -B -Dtest=KtvFilenameParserTest test
# 完整后端回归由仓库根 make java-backend-test 执行
```

扩展标签时修改 `TagDictionary` 的精确别名或待复核提示，同时补充字段断言；提示词只进入未识别片段。扩展结构时实现 `FilenameRule`，在 `FilenameRules.RULES` 注册顺序和明确优先级，定义规则标识及基础分，在 `FilenameSamples` 增加样本，并补充与既有格式冲突的测试。评分规则集中在 `ConfidenceCalculator`，不在单条规则里自行填充最终高分。

真实共享文件名验证的本轮数量、结果和边界见[验证记录](../../docs/verification.md)。批量验证工具与文件名快照只保存在临时目录，未将目录扫描或 SMB 访问代码加入项目。

## 共享规则库、解析方案与兼容配置

“曲库 → 解析规则”维护单条模板/RE2 正则；“解析方案”引用定义并维护启停、顺序和文本预览。规则修改影响所有引用方案，删除被引用规则返回 409 和 referencedBy；默认方案不能删除，其他方案可复制/改名/删除。

存储沿用 `filename_parser_config` 表。rules_json 在首次成功修改时升级为 schemaVersion=2（definitions + profiles），GET 只投影旧数组，不写库；全局 revision 保护全部修改和引用竞争，不修改已应用 SQL 迁移。旧 `/rules` 读写投影默认方案，省略规则不会删除其他方案依赖；旧 `/preview` 草稿及单参数纯解析行为保持。

新增 `/api/v1/filename-parser/rule-definitions`、`/profiles` CRUD 与 `/profiles/preview`，复用管理员会话和 CSRF，原规则引擎及 RE2/J 1.8 不变。方案预览展开稳定引用形成不可变 FilenameRuleSet；媒体来源已关联解析方案，并通过独立本地扫描服务提取及保存歌曲元数据。使用、数据格式、接口与限制见[规则与方案说明](../../docs/filename-rules.md)。

## SQL 与 Mapper XML

采用 `mybatis-spring-boot-starter:3.0.5`，业务 SQL 显式存放于 `src/main/resources/mappers/{auth,media,infrastructure}/`，按模块维护 Mapper 接口和同名 XML。Java 负责业务流程与参数，不内联业务 SQL，也不使用 SQL 注解或 Provider；参数通过 `@Param` / `#{}` 绑定。Spring 继续管理事务，保留单连接 SQLite 池、MySQL Profile、dbmate 迁移和既有 HTTP 契约。新增 SQL 时同时更新 Mapper 接口与 XML，使用 `make java-backend-test` 验证。详细规则见根 `AGENTS.md` 与[数据库策略](../../docs/database-strategy.md)。

## 本地歌曲扫描

媒体来源 DTO 增加 `parserProfileId`，方案解析使用 `FilenameParserConfigService.savedProfile` 的不可变快照。`MediaScanService` 提供有界本地目录遍历，`MediaSongRepository` / `MediaSongMapper.xml` 持久化解析结果；`POST /api/v1/media-sources/{id}/scan` 触发，`GET /api/v1/media-sources/{id}/songs` 分页查询。SQLite 默认测试覆盖完整流程；`mysql-check` 同时包含 `MySqlMediaScanIT`，需真实测试库方可验收。限制与事务语义详见[媒体库管理](../../docs/media-library.md)。

歌曲管理由 `MediaSongController` / `MediaSongService` 提供 `GET /api/v1/media-songs`，分页查询所有扫描索引，可按来源、文件名、复核状态筛选；列表 SQL 与计数共用 `MediaSongMapper.xml` 的绑定参数条件，查询在同一事务中执行。`202610280003` 回填复核标志并建索引，已有扫描歌曲无需重扫即可显示。

## WebDAV 后台文件名扫描

`media.webdav` 提供受管理员会话/CSRF 保护的 `scan-tasks` API、仅使用 PROPFIND 的流式客户端、两个线程的有界执行器及数据库暂存。支持 OpenList 和单来源十万首，旧索引在扫描期间保留，成功后原子替换；重启中断后手动重扫。`MediaSong.fileSize/modifiedAt` 可为 null。目录响应不触发视频 GET/HEAD、下载、Python 或外部元数据服务。限制、恢复、接口及 OpenList 配置见[媒体库管理](../../docs/media-library.md)。

`WebDavClientTest` 与 `WebDavScanHttpTest` 使用本机随机端口和临时 SQLite 验证；`MySqlWebDavScanIT` 纳入 `mysql-check`，需配置真实隔离 MySQL 才能验收。十万首测试只生成 XML 目录条目，不创建视频。

## 歌曲元数据刮削

`media.metadata` 提供网易云/QQ/酷狗元数据适配、持久化批次、候选审核和独立确认覆盖层；`MetadataMapper.xml` 负责业务 SQL。功能及自动写入默认关闭，支持单曲、选中及全库批次，暂停/续跑/终止、失败重试和重启恢复。数据库迁移 `202610280004`；封面缓存可用 `METADATA_CACHE_PATH` 配置。实现边界、启用及写入规则见[元数据刮削](../../docs/metadata-scraping.md)。

文件标签及技术探测字段（包括仅有时长）不算已确认的平台元数据，不阻止批次匹配及补齐封面、专辑等缺失字段；文件字段仍优先保留。历史记录若因“查询期间歌曲已确认”误标为已确认应用，可在批次中重新匹配；后端仅在当前没有有效确认元数据时允许恢复，已终止批次仍需新建批次。全部歌曲与原唱/伴奏列表复用同一份已保存结果，候选预览图片不代表封面已经应用。

批量刮削可在平台设置中配置每首完成后的随机等待范围（默认 3～8 秒，允许 2～300 秒，含端点；最大值不得小于最小值）。设置持久化且保存时暂停活动批次，续跑使用当前间隔；等待不占用数据库连接。同一平台请求仍保持串行及至少 1.5 秒启动间隔；HTTP 429 会触发该平台至少 60 秒冷却，期间拒绝新请求，失败项可稍后手动重试。单曲搜索也受平台冷却约束，批量随机等待只约束后台批次。随机间隔用于平滑流量，不能保证避免限流或自动化识别。

媒体来源与歌曲列表已采用独立搜索区、分页表格及可选每页条数；来源支持名称/地址、类型、方案筛选，查询使用 ID 分页后延迟关联和筛选排序联合索引。深分页成本与包含搜索边界见[列表查询说明](../../docs/library-search.md)。

媒体来源添加/编辑支持定时扫描开关及标准五段 cron（上海时区）；配置与下次触发持久化，同来源扫描中跳过，停机遗漏不逐次补跑。使用及 API 字段、调度限制见[定时扫描](../../docs/media-scan-schedule.md)。

单曲元数据已使用独立双栏审核工作台，支持字段编辑、受管 JPEG/PNG 上传、平台封面搜索及统一确认应用；旧批量审核入口保持独立。新增接口、资源备份、幂等与版本校验规则见[单曲审核](../../docs/single-song-review.md)。

批量工作台已统一整体进度：total/completed 均包含跳过项，新批次持久化跳过明细，旧批次仅汇总并提示。新增批次内搜索/排序、可信度审核提示及按状态操作，自动写入和限流规则不变。接口兼容说明及统计定义见[批量工作台](../../docs/batch-metadata-workbench.md)。

歌曲分类接口 `/api/v1/classification` 使用独立的持久化任务与 MyBatis XML，支持兼容 Chat Completions 的 JSON Schema 模型、分类锁定/审核/撤回和歌曲标签筛选。SQLite/MySQL 自动迁移；配置默认关闭。详见[分类说明](../../docs/song-classification.md)。


## 歌曲位置与本地目录选择

管理端“歌曲位置”以卡片呈现来源，统一添加入口；定时扫描、解析方案收进“更多设置”。本地文件夹通过 `GET /api/v1/media-sources/local-directories?path=...&page=0` 浏览选择，WebDAV 仍填写地址。名称在前端留空时按文件夹名生成，API 的 name 仍为必填。

目录接口仅管理员会话可用，范围与已有本地来源保存相同：服务进程有权读取的目录，不改变权限或挂载。空 path 列出文件系统根目录；绝对路径返回规范化 path、parent、items（name/path）、hasMore。parent 为 null 表示根目录列表，为空字符串表示返回该列表。仅列可读子目录，略过符号链接，不读文件内容；按目录名排序，每页固定 100 条，page 从 0 开始且不超过 10000。路径无效、不存在或无法列目录返回 400，未登录返回 401。Docker 中仅能看到容器目录，NAS 歌曲目录须预先只读挂载，不能通过浏览器选择未挂载的宿主机目录。


## 管理端在线播放

歌曲列表行内操作统一为按钮，新增“在线播放”，在带原生控件的视频弹窗中播放当前歌曲。关闭弹窗或离开页面移除 video 元素以停止读取；错误时可重新加载。

`GET /api/v1/media-songs/{id}/video` 与 HEAD 复用管理员 Cookie 会话，未登录返回 401，不提供公开媒体 URL。支持单段 Range（起止、开放结束、后缀范围），本地响应 200/206/416 和 Content-Length/Content-Range；非法范围或多范围返回 400。所有流响应禁止缓存。本地解析真实路径并限制在来源目录内，失效文件或越界符号链接返回 404。

WebDAV 使用保存的地址和加密凭据在服务端发起 GET/HEAD，转发 Range，不把凭据或远端地址返回浏览器。采用 10 秒连接、30 秒读取空闲超时，流式传输，不落盘、不完整缓冲；连接随响应结束/客户端断开清理。上游忽略 Range 时保持 200，拖动能力取决于上游；不跟随 3xx（返回 502），需要支持直接读取的 WebDAV 服务，OpenList 等返回下载跳转的配置需调整，否则暂不能播放。认证/读取错误返回不含远端细节的 502。

原始音视频接口继续只读直放；管理端和 TV 已通过 Java 协商 compute 转码或离线兼容版本，不因预览创建电视会话。浏览器能否解码取决于容器与视频/音频编码，MP4/WebM 也不保证所有编码都能播放，MKV/AVI 等可能不可播放。Nginx 对视频接口关闭 proxy_buffering/proxy_cache，避免视频临时文件与额外等待；代理读取空闲超时为 60 秒。真实 NAS、WebDAV 提供者、浏览器编码兼容性仍须单独验收。


## 音频文件扫描与在线播放

本地目录与 WebDAV 文件名扫描统一支持 MP3、FLAC、WAV、M4A、AAC、OGG、OGA、Opus、WMA、AIF、AIFF、APE，扩展名不区分大小写，沿用现有解析方案提取歌名和歌手。扫描只建立文件名索引，不读取音频内容或内嵌标签；新增格式需重新扫描才会进入歌曲列表，现有扫描数量限制不变。

“在线播放”按扩展名选择音频/视频控件。音频复用已鉴权的 GET/HEAD `/api/v1/media-songs/{id}/video`（为兼容保留原路径），返回 audio/* 媒体类型，沿用 Range、后端 WebDAV 代理、凭据保护与关闭停止加载。音频失败可重试，切换或关闭时移除旧媒体元素。不能直放时可使用兼容播放或离线版本；实际支持取决于 compute 工具与源编码。WebDAV 下载重定向限制不变。


## 共享 AI 服务

`com.mynora.singverse.ai` 管理模型/搜索协议适配、服务配置、密钥、测试和功能绑定，公开管理员专用 `/api/v1/ai`。业务 SQL 位于 `mappers/ai/AiMapper.xml`，SQLite/MySQL 迁移为 `202610290001_ai_settings.sql`。歌曲分类通过绑定读取模型，保留原配置接口兼容适配及任务快照；业务输入、结果校验、审核和应用仍属于 classification 模块。

外部 HTTP 请求由 Java 发起，不依赖 Python 或前端直连厂商。服务地址与密钥保护、协议边界、迁移及通用搜索契约见 [AI 设置](../../docs/ai-settings.md)。分类仅复用模型服务，本次不使用搜索或增加聊天能力。


`assistant` 功能配置复用 `/api/v1/ai/features/assistant`，保存 enabled/displayMode/petId；新迁移同时覆盖 SQLite/MySQL。仅保存展示设置，不接受模型/搜索绑定，不暂停分类任务，不新增聊天或工具执行 API。

## 本地扫描内嵌标签

Java通过 `infrastructure.compute.MediaTagsClient` 请求独立计算服务，不安装或启动FFmpeg/Python。配置 `COMPUTE_NODE_URL` 与只读内部凭据文件 `COMPUTE_AUTH_TOKEN_FILE`；媒体路径必须同时挂载到计算服务的白名单内。Compose默认已连接内部服务，未配置/离线时保留文件名回退。

文件非空歌名/歌手/专辑/发行日期优先，其他字段沿用有效人工/平台结果，再回退文件名。快照保存在media_song.embedded_tags_json，SQLite/MySQL同步迁移；origins.kind=FILE标记文件来源，审核应用保留这些字段。原文件不修改，原始文件名解析证据保留；重新扫描读取新标签，移除标签不保留旧FILE来源字段。WebDAV不下载媒体。详见计算服务README。

## 电视与聚会

`tv` 模块使用独立 Bearer 身份，保留管理员 Cookie/CSRF。设备授权、参与者、邀请、命令和单电视聚会聚合持久化在 `tv_record`，SQL 位于 `mappers/tv/TvMapper.xml`；并发修改先获取数据库写锁，再用修订号 CAS 保存。SQLite/MySQL 迁移为 `202610290005_tv_party.sql`。聚会操作幂等，播放遥测使用独立 eventSeq；下一首必须由电视确认交接。

设备播放复用 MediaPlaybackService，继续只读本地文件与代理 WebDAV，支持 Range；另通过 compute 协商实时 HLS 与离线兼容版本。只读曲库投影裁剪路径及管理信息，分类复用正式标签，歌手精确署名聚合在服务端完成。详见[TV 设计与边界](../../docs/tv-development-design.md)及[OpenAPI](../../contracts/openapi/README.md)。

TV 首页 `/api/v1/tv/home` 复用 MyBatis 曲库分页查询，独立返回全库数量和最近扫描的 8 首歌曲，不受客户端当前搜索影响。`/api/v1/tv/library/songs/{id}` 提供实时详情投影，使用已保存的有效元数据与标签，包含可用专辑、发行日期、时长及展示修订标记。两接口及权限、404、更新后可见性由隔离 HTTP 测试验证；不增加业务表或计算能力。


### TV 非转码增强

TV/亲友只读曲库支持有效元数据、拼音全拼/首字母及已解析编号检索（TvLibrary，共享 30 秒只读索引），正式标签过滤仍同维 OR/跨维 AND，先过滤后分页。新增设备专用同名 LRC/SRT/VTT 文本读取和发布目录的 manifest/APK 接口，具体字段见 OpenAPI；来源凭据不对外，文本每个上限 128KiB，发布包上限 256MiB。没有新增业务表，SQL 仍通过现有 TvMapper.xml。

`SINGVERSE_TV_RELEASE_DIRECTORY` 默认空，不启用发布；配置为 Java 可读的专用发布目录，包含 `release.json`（versionCode/versionName）和 `singverse-tv.apk`。客户端仍验证同签名/递增版本，不靠服务端摘要代替签名。Docker 的变量是容器内路径，步骤见 TV README。

原生部署可显式设置 `SINGVERSE_TV_DISCOVERY_ADDRESS`（本机内网绑定地址）与 `SINGVERSE_TV_DISCOVERY_PORT`（电视可达的实际 HTTP 入口，默认18080），发布 `_singverse._tcp.local.`。默认不发布；桥接 Docker 应从宿主机 Avahi/Bonjour 发布，不能用容器 IP 宣称手机或电视可达。发现失败不影响手动连接，广告只含服务与端口信息。


### 分类封面持久化

新增SQLite/MySQL迁移202610290006_classification_covers，classification_tag_cover单表原子保存规范化JPEG和SHA-256版本，备份业务数据库即包含分类封面，不写用户媒体目录。SQL位于ClassificationMapper.xml，列表仅联查版本，不读取图片BLOB。管理员GET/PUT/DELETE `/api/v1/classification/tags/{id}/cover`；写入需Cookie+CSRF和当前封面版本（首次上传为空），独立于标签revision，冲突409。PUT multipart字段file/version，JPEG/PNG最多2,000,000字节、单边最多4096px，规范化最长边1280pxJPEG，不信任文件名/MIME声明，不接受SVG或远程URL。

TV/有效参与者GET `/api/v1/tv/library/classification-tags/{id}/cover`只读启用标签，未上传/已移除/已停用返回404，未授权401。正式标签投影含coverPath/coverVersion，图片响应no-store/nosniff。无转码任务或Python变更。

## 自动歌手库

扫描后按歌曲有效署名自动生成，不再人工建档/关联。管理端歌手详情可打开歌曲编辑器订正歌手，NAS 覆盖值优先于文件标签并在重新扫描后保留；恢复扫描值需确认。首次后台从网易云/QQ 核对歌曲并取真实歌手头像，失败有限重试，无可靠匹配保留占位。上传图片优先，移除后暂停自动补图。TV 自动展示同步结果和受权图片，保留返回焦点。迁移 `202610290008`，新增接口、来源与状态见 OpenAPI 和 [自动歌手库说明](../../docs/artist-library.md)。

歌手图片补图迁移 `202611010001` 将头像/背景状态分开，并接入 TheAudioDB 与可选 Fanart.tv。Fanart.tv 个人密钥在管理页「图片来源设置」中加密保存；后台只在核对歌手身份与关联歌曲后自动用图，歧义候选由管理员确认。外部图片仍由 Java 下载、校验并通过 NAS 鉴权接口读取。

## 共享歌单与 AI 建议

管理员 `/api/v1/playlists` 分页/新建，`/{id}` 读取/编辑/删除，`/generate` 仅生成建议预览；TV `/api/v1/tv/library/playlists`、`/{id}`、`/{id}/songs` 只读。UUID 创建去重，revision 编辑/删除保护；已移除歌曲保留引用并在 TV 跳过。迁移 `202610300001`，SQL 见 `mappers/playlist/PlaylistMapper.xml`。复用 AI 模型配置与多协议客户端，不额外建立 AI 功能绑定或服务；只传候选标题/歌手/分类/ID，不传路径、凭据和媒体，返回曲库外 ID 拒绝采用。细节见 [歌单说明](../../docs/playlists.md)。

## 手机原生曲库只读接口

`mobile.MobileLibraryController` 提供管理员 Cookie 鉴权的 GET `/api/v1/mobile/library/songs`、`/{id}`、`/{id}/text-tracks`。列表支持 q/page/size/tagIds/unclassified/artistId，复用现有曲库拼音索引与分类/歌手筛选；返回媒体条目而非按同名合并的歌曲组。DTO 补充 mediaType/format/language，未知可播状态保持 UNKNOWN，封面为已有管理员资源路径。歌词复用受限来源内同名 LRC/SRT/VTT 读取，不读取任意路径。

手机继续使用现有 auth、artists、classification/tags、playlists 和 transcoding 接口，不创建 TV 设备/聚会，不新增表或业务 SQL。`MobileLibraryHttpTest` 在随机端口和隔离 SQLite 验证鉴权、真实扫描、分页、中文/拼音、分类及歌手筛选、来源边界与文本大小限制。

## WebDAV 增量索引与内嵌元数据

WebDAV 扫描现在按“来源＋相对路径”增量合并，扫描完成即发布目录索引，独立的元数据任务不会阻塞扫描。普通重扫复用已完成、无标签、部分完成、超限和不支持结果，不向已有文件发送媒体 GET/HEAD。新增、复用、待确认删除和下架数量随扫描任务返回。

缺失文件在至少间隔 30 分钟的两次完整成功扫描确认后下架，缓存和关联保留 30 天；保留期内原路径恢复时复用缓存。失败、取消、超限不推进删除。来源突然为空时保留索引，两次空扫描确认后须在“整理与分享”中明确确认全部下架。原件始终只读。更换来源根地址会隔离旧索引与缓存；移动/改名按新路径处理，不猜测跨路径内容相同。

已有来源默认关闭内容提取。在媒体库卡片“整理与分享”中启用后，已索引歌曲逐步排队补齐；支持暂停/继续、访问组合并及私有下载代理来源白名单。歌曲详情显示提取覆盖状态、技术信息、内嵌封面和歌词；列表可批量刷新或深度提取。大小、mtime、ETag 变化只提示可能变化，不自动读取；同路径覆盖文件需手动刷新。文件版本指纹在普通目录属性更新时保持稳定，保留已确认信息和衍生版本的兼容性。

Java 编排、持久化任务和共享限流；compute 从任务授权的 `/api/v1/internal/metadata-input/{id}` 获取有界 Range，不获得 WebDAV 凭据或任意外部 URL。新内部接口仅接受节点 Bearer 与活动任务引用。需要 compute 的 `media-remote-tags` 能力及 `COMPUTE_JAVA_ORIGIN`；保留原本地标签接口。读取同一会话使用强 ETag 条件请求；长度、版本、范围或授权变化时丢弃不稳定结果。

默认限制：同一访问组目录和媒体请求串行、间隔至少 2 秒并抖动；文件任务间隔至少 10 秒、全局提取并发 1。普通单文件 16 MiB / 16 次请求 / 60 秒，深度提取 128 MiB / 64 次 / 180 秒；不设每日累计首数或流量额度，开启后持续处理待办。日统计仅用于查看处理量，不阻止调度或读取。手动刷新优先于普通后台待办，两者都保留请求间隔、单文件上限和异常冷却。网络暂时失败最多重试两次，间隔 30 分钟、6 小时；401/403 等待管理员处理；429 至少冷却 6 小时，并遵循更长 Retry-After。来源不支持 Range 时立即停止，不完整下载。代理上游实际流量及供应商风控仍需实测。

最多跟随三次下载跳转；跨来源不携带 Authorization、不恢复凭据，不允许 HTTPS 降级。跨来源私有代理须配置精确 HTTP(S) origin，拒绝链路本地、多播、未指定地址。短期下载 URL 仅保留在当前读取请求内，不保存到库、日志或 STRM；本次不生成 STRM。

可选部署参数：`WEBDAV_METADATA_PATH` / `singverse.webdav.metadata-path`（默认 `./data/metadata/webdav`，独立于原件保存封面和歌词）；`singverse.webdav.metadata-worker-enabled`（默认 true，来源仍需单独启用）；`singverse.webdav.request-interval-ms`（默认 2000，测试可设为零，生产不建议降低）。Docker/Compose 固定为 `/data/metadata/webdav`，复用持久命名卷保存衍生资源。内嵌封面复用现有图片校验，按内容哈希存储；歌词保留原有文本及时间格式，不进行截图、OCR、联网刮削或播放器逐字渲染。

API：管理员 GET/PUT `/api/v1/media-sources/{id}/embedded-metadata`；POST 同路径 `/confirm-empty`；GET `/api/v1/media-songs/{id}/embedded-metadata` 及 `/cover`；POST `/api/v1/media-songs/embedded-metadata/refresh` 接收 `{songIds, deep}`（最多 100 首）。所有管理员写请求要求会话和 CSRF，接口细节见 OpenAPI。SQLite/MySQL 使用 `202610310001_webdav_metadata.sql`；所有业务持久化 SQL 位于 MyBatis XML。重启延后恢复未完成提取，已完成结果不重做；进程在读取完成但提交前崩溃时，无法保证严格只读取一次。

验证入口：`make java-backend-test`、`make compute-check`、`make frontend-check`、前端 `npm test`；可用真实隔离 MySQL 时执行 `make db-mysql-check`。WebDAV 专项测试包含十万首扫描、删除恢复、空库确认、重复扫描零媒体请求、刷新代次、内部鉴权、版本与请求预算。模拟 OpenList/网盘不能替代供应商真实验收。

预算统计按 Java 接收的媒体响应体字节累计，不包含 HTTP/TLS 头或网盘代理内部的额外流量。开始文件前为完整单文件预算留足当天余量，余量不足则等待次日，不因日配额不足把该文件永久标为部分完成。下载会话设置整体截止时间，避免慢速响应通过持续发送少量数据无限延长读取。

## 可分享的媒体索引包

本地目录和 WebDAV 统一将可用的文件标签、已应用的刮削结果及人工订正投影到 `WEBDAV_METADATA_PATH/library/{sourceId}/{songId}/`。每首包含 `media.strm`、`media.nfo`、`metadata.json`，有封面/歌词时同时保存图片、歌词 JSON 和可用的 LRC/文本；字段来源和相对路径保留在 JSON。本地 STRM 引用实际本地媒体路径，WebDAV STRM 使用可替换占位根地址；分享时始终重建占位 STRM，不泄露本机绝对路径。原媒体文件及旁边目录均不写入。

保存元数据与衍生文件待办在同一数据库事务中提交；后台每批最多 250 首逐首原子替换单个文件，以刷新代次确认同步，失败保留待办并至少间隔五分钟重试，服务重启可继续。新编辑会合并待办并立即允许重试。导出请求自动进入 WAITING，正文文件保存后后台固定快照，其他来源的导出不被等待任务阻塞；主界面只显示“正在准备”。暂不可用的封面记录独立待补齐状态，不阻塞正文或分享；大 PNG 自动缩小至 2 MB 内，原封面不修改。已生成分享包保持快照；再次编辑后需重新导出或增量分享。尚未采用的刮削候选不写正式文件；原有字段合并规则仍适用。

媒体库 WebDAV 卡片 → 整理与分享 → 分享曲库。后台生成版本 2 索引包，包含 manifest.json、STRM、musicvideo NFO、封面及歌词；未提取的条目使用明确标记的文件名回退。不包含密码、来源 ID、用户账号或临时下载 URL。STRM 使用 https://replace.invalid/dav/ 占位地址，外部播放器使用前替换根地址；播放器兼容性需自行验收。

接收方先把媒体挂载到自己的 OpenList，添加对应的 WebDAV 根目录，再选择收到的整套 ZIP，系统自动导入；无需手动解压或挑选分包。相对路径映射到接收方根目录，无需先扫描或重新探测媒体；完成后可直接查看歌曲。导入记录标为 IMPORTED / SHARED，界面显示“共享信息待核对”。已有歌曲和保留的下架记录跳过，后续人工订正优先于共享缓存。索引包不授予原媒体访问权限。

单个分包最多 5000 首，压缩和展开均不超过 32 MiB；整套上传压缩及展开累计最多 8 GiB，最多 4096 个分包，逐包流式处理，不将整套 ZIP 缓存在内存或 Nginx 临时文件。校验清单顺序、重复路径、路径越界、图片和字段长度，失败回滚当前批次，已完成检查点保留。不执行 STRM/NFO 地址或链接。POST /api/v1/media-sources/{id}/embedded-metadata/share 接受两种版本 2 ZIP；管理员会话与 CSRF 必须有效。Nginx 仅此路径允许 8 GiB 且关闭请求缓冲，上传/处理空闲超时最长一小时。

## 十万首整库分享

媒体库 → 整理与分享 → 分享曲库 → 生成完整分享包。后台在数据库事务中固定完整索引快照，按歌曲 ID 游标读取，每次最多 50 条；每包最多 5000 首，按内容估算提前分包，压缩及展开仍各限制 32 MiB。包文件持久保存在 WEBDAV_METADATA_PATH/shares/，逐包原子写入并保存游标，服务重启继续；失败可点继续。每来源最多一个活动导出，全局最多八个活动任务。已完成/失败快照及分包保留七天，活动增量任务引用的基线不会被清理。磁盘不足 64 MiB 时停止并保留检查点。

“下载分享包”流式组合总清单及全部分包，不把整库压入内存。接收方直接选择该 ZIP 导入；高级选项保留单独下载分包和仅分享变化。元数据、STRM、NFO 和歌词按相对路径输出，封面按内容哈希跨包去重，首次出现的包携带封面，后续包引用同一资源。外部播放器仍需解压所有分包至同一目录并替换 STRM 占位根地址。

支持按原分包文件名排序一次选择多个 ZIP。服务端每批最多 100 首且按附件体积进一步拆小，在同一数据库事务内提交歌曲和包哈希检查点；连接中断、关闭页面后重新选择全部包即可接续，重复完成的包不重复写入。未上传的分包需要重新选择上传，浏览器不在时不会自行读取本机文件。先导入前面的封面所属包，缺少引用附件会明确报错，不联网补图。已有歌曲和人工订正继续跳过。

选中上次成功快照可增量导出。新快照仍保存完整索引，仅将新增或改变条目写入分包；缺失路径输出独立 advisoryOnly 删除提示，接收方不会自动下架歌曲。无变化时分包数为零。尚未提取内嵌信息的歌曲使用文件名解析信息作为共享回退，明确标记 evidence=FILENAME，避免为分享触发整库网盘读取。导入结果仍标为 SHARED、未经本地媒体验证。

迁移 202610310002_webdav_share_jobs.sql 同时支持 SQLite/MySQL；API 为 /api/v1/media-sources/{id}/embedded-metadata/share-jobs（创建）、/latest、/{jobId}、/{jobId}/resume、/{jobId}/download、/{jobId}/index、/{jobId}/parts/{part}、/{jobId}/deletions/{part}。导入支持持久检查点，不保留旧版单包导出接口。

## 全曲库共享音轨角色

原唱/伴奏任务的 `SUCCEEDED` 仅代表执行完成，`resultState` 才是识别结论。完成但不确定的 FAST/ANALYZE 可显式重新提交，新任务保留旧历史；活动任务及已明确识别的结果仍复用。音轨状态响应透传 `reason`、`evidence`，兼容没有这些字段的旧记录。扫描后台先 PROBE，再为未确认的双轨歌曲安排 FAST（标签优先、短片段分类），不再自动安排 ANALYZE 深度分离。已有不确定深度历史也可获得一次快速识别；FAST 不确定结果不循环重试，FAST_MODEL_MISSING 且节点新增分类器能力时自动再试一次；节点宣布 `audio-role-fast-v2` 后，旧版本 UNKNOWN 也可自动补跑一次，最新 v2 UNKNOWN 不循环重试。快速结果不覆盖任何已确认标记；人工及共享标记继续由修订/指纹规则保护。

迁移 `202611010002_audio_roles.sql` 保存角色、修订号、素材指纹、离线任务及待核对的分享声明。扫描产生的歌曲由后台游标异步探测，双音轨使用快速识别；无分类器时仅依赖明确的成对音轨标签，不确定则保留待标记。单音轨只由管理员在歌曲详情按需生成伴奏。原件不改写；Java 用 MyBatis XML 保存业务结果、校验下载的 SHA-256/体积并原子保存独立衍生文件。配置 `AUDIO_DERIVED_PATH`（默认 `./data/audio-roles`），必须独立于原媒体，Compose 已指向持久卷。

`GET/PUT /api/v1/media-songs/{song}/audio-roles` 查询/纠正；提交修订号及两条不同且已探测的源 stream ID。MANUAL 优先于 AUTO；旧素材结果失效，修订号竞争返回 409。`POST .../audio-jobs` 提交任务，`POST .../audio-jobs/{id}/cancel` 取消。亲友及 TV 对应路径为 `/api/v1/{party|tv}/audio-roles/songs/{song}`，使用已加入聚会的 Bearer；亲友可纠正但不能提交分离任务。管理员写入继承 Cookie/CSRF 规则。

播放协商接受 `audioRole=original|backing`，返回 `audioRoles` 和可用的 `backingUrl`。手机/TV 通过同一播放器合并原视频与独立伴奏，按照协商 offset 对齐原时间轴；纠正仅影响下一次选用。状态轮询不重复返回角色快照，客户端保留首次协商结果。节点离线仍可播放 Java 已保存的伴奏。

WebDAV version 2 分享索引的 `audioRoles` 仅含源轨映射、轨道描述、时长、来源及 `separationPolicy=GENERATE_LOCALLY`，不携带任务、主机路径、节点地址或分离音频。接收方以原索引相对路径定位媒体，重新探测并核对轨道/时长后应用为 SHARED；本地 MANUAL 不被覆盖。旧 v2 包缺省该字段可继续导入；生成伴奏仍由接收方管理员提交。

### 原唱与伴奏管理中心

管理员 `/api/v1/audio-processing` 提供歌曲筛选、批次快照/提交/详情/取消/失败项重试，以及当前节点和模型操作代理。迁移 `202611010003_audio_processing.sql` 新增批次/成员及任务后端字段，全部业务SQL保留MyBatis XML。预览快照保存24小时；提交幂等、媒体变化失效、未知音轨先探测后分流，取消不删除已完成音频。批次成员分页，整体取消同步停止所拥有的分离任务，复用的公共探测不因取消批次被终止。生成环境未就绪时不能提交批次。

离线模型通过 `/model/import?model=htdemucs|htdemucs-openvino` 的 `application/octet-stream` 流式转发；省略参数兼容基准模型。Java 和 compute 按固定清单限制基准权重 100MiB、OpenVINO 双文件 ZIP 128MiB，Nginx 只为该路径放宽至 128MiB 及相应超时。未知模型拒绝，下载代理同样仅接受固定模型 ID。任务结果保存实际后端、模型摘要、构建与环境身份，OpenVINO 不会隐式回退 CPU。管理员会话及CSRF继续生效，客户端不能直连compute。模型管理以实际当前节点为准；旧任务保留原节点绑定，切换连接不会把旧任务重复发给新节点。节点离线保持待核对，配置变化明确失败供重试。列表和全部筛选快照目前使用分段遍历核对有效歌曲信息/素材指纹，超大曲库需专门压测，不宣称已实现数据库索引化全文筛选。

扫描识别任务新增 `FAST`，仍由 Java 授权代理、MyBatis XML 持久化与 compute 执行；沿用既有 kind 文本字段，无新增数据库迁移。节点状态透传 `fastClassifier`，固定模型下载/ZIP 导入 ID 为 `panns-cnn6`，上限 32MiB。深度识别 `ANALYZE` 与伴奏生成 `SEPARATE` 的硬件优先/CPU 降级策略保持既有规则。
