# SingVerse Go 业务服务

Go 1.27.3 / Gin / `database/sql + sqlx`。主服务负责授权、曲库、任务、TV/亲友队列与唯一业务数据库写入；Python 负责媒体探测、转解码和模型执行。完整 Vue 管理页与亲友 H5 嵌入二进制，生产无需 JVM、Node 或 Go 编译器。版本由根 `VERSION` 注入，依赖由 `go.mod`/`go.sum` 锁定。检查工具固定为 staticcheck `0.8.2` 与 govulncheck `1.9.0`，安装到仓库 `.tools/`，不修改全局 Go 环境。

## 构建与运行

从仓库根执行：

```sh
make go-tools-setup dbmate-setup
make go-backend-build
make go-backend-run
# 检查版本
services/singverse-go-backend/bin/singverse --version
```

构建先使用 `npm ci` 与完整前端构建，再准备 `internal/web/dist`，缺少页面必须失败。二进制以 `CGO_ENABLED=0`、`-trimpath` 构建；本机 LX 音源在同一可执行文件的隔离子进程中运行。该 worker 不开放网络、不读取业务配置或数据库，宿主提供受控协议。

原生默认 `127.0.0.1:8080`，直接托管 `/`、`/admin/` 与 `/party/`；业务 `/api/v1`、健康 `/api/health`。首次空库 `admin/admin`，登录后改密。配置只读取显式进程环境，不自动加载根 `.env`。

| 变量 | 默认与含义 |
|---|---|
| `SINGVERSE_DB_DRIVER` | `sqlite`；可显式选 `mysql`，失败不回退 |
| `SQLITE_PATH` | `./data/singverse.db`，相对服务工作目录 |
| `MYSQL_HOST/PORT/DATABASE/USER/PASSWORD` | 可选 MySQL 配置，密码必填 |
| `SINGVERSE_BIND_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8080`；容器显式私网监听 |
| `SINGVERSE_AUTH_KEY_FILE` | 默认数据库旁 `auth/master.key`；必须与密文匹配 |
| `DBMATE_BIN` / `SINGVERSE_MIGRATIONS_DIR` | 默认仓库 `.tools/dbmate` 与 `database/migrations` |
| `COMPUTE_NODE_URL` / `COMPUTE_AUTH_TOKEN_FILE` | AUTO 内部节点；地址为空禁用探测 |
| `SINGVERSE_CALLBACK_ORIGIN` | compute 可达的业务入口，无末尾斜杠 |
| `WEBDAV_METADATA_PATH` / `METADATA_CACHE_PATH` | 独立元数据衍生、共享包和封面目录 |
| `TRANSCODE_VERSION_PATH` / `AUDIO_DERIVED_PATH` | 独立兼容版本与音轨衍生目录 |
| `SINGVERSE_SONG_CACHE_PATH` | 业务节点持久缓存；预算配置见根 `.env.example` |
| `SINGVERSE_PUBLIC_ORIGIN` / `SINGVERSE_TRUSTED_PROXIES` | 显式 HTTPS 来源与受信代理，默认不信转发头 |
| `SINGVERSE_DIAGNOSTICS_ADDRESS` | 可选，必须显式回环 IP；独立诊断 listener |

SQLite 创建必要父目录，单连接；启动先取得部署锁并执行固定 dbmate 迁移。SIGINT/SIGTERM 停止接收 HTTP、取消后台任务并等待退出。HTTP 带请求 ID，日志为 `slog` JSON；错误不泄露 SQL、凭据或上游响应。诊断接口不挂公开路由。

## 代码约定与验证

模块按 `internal/<domain>` 分开，应用在 `internal/app` 显式组装，禁止服务定位器。业务 SQL 放模块 `sql/*.sql`，仅参数绑定；连接使用 `*sqlx.DB`，事务通过 `BeginTxx` 创建 `*sqlx.Tx`，跨模块复用 `store.DBTX`，SQLite 事务内不能另取连接。固定结构用独立数据库行模型、显式 `db` 标签与 `GetContext` / `SelectContext` / `StructScan`；流式读取继续逐行处理，避免整库额外副本。通用投影由 `store.Maps` 统一使用 sqlx `MapScan`，保留 MySQL 文本、BLOB、空值及空列表的原有语义。`sqlx` 固定为 `v1.4.0`；现有 `@{name}` 仅作嵌入 SQL 标记，由平台适配到 sqlx 命名参数绑定，值不进入 SQL 文本。网络请求、目录遍历、模型执行不占长数据库事务。文件发布、索引与任务状态通过短事务和有界 worker 协调。

阅读核心流程时，可从以下入口逐步向下追踪：

| 流程 | 代码入口及职责 |
|---|---|
| 启动与关闭 | `internal/app/app.go` 管理生命周期；`services.go` 按依赖顺序创建服务并登记关闭顺序；`adapters.go` 转换跨模块回调数据 |
| HTTP 与观测 | `internal/app/http.go` 装配路由和认证边界；`observability.go` 管理请求日志及诊断指标。Gin 在注册时固定中间件，compute grant 与 Bearer 路由先于管理员边界注册 |
| 聚会与播放 | `internal/party/http_*.go` 按设备、邀请、会话和播放资源分组；`queue.go` 负责幂等回执、修订检查及权限分派，`session_commands.go`、`playback_commands.go` 和 `playback_events.go` 分别处理主持操作、电视指令和播放回报 |
| 后台任务投影 | `internal/background/projection.go` 串联读取、构建和持久化；`projection_details.go` 映射各任务类型，`projection_progress.go` 处理计数及时间，`projection_items.go` 分页同步明细和关联子任务，全程复用调用方事务 |
| 媒体执行 | `internal/execution/http.go` 汇总音轨、批次、模型、转码和缓存路由，处理函数位于对应 `http_*.go`；模型导入在 `models.go`，缓存任务执行及等待在 `cache_cleanup.go` |

新增或整理复杂流程时，按实际业务步骤提取函数，使用能表达角色的局部变量名；路由注册保留路径与处理函数的对应关系。注释着重解释幂等顺序、事务、授权和状态边界，业务 SQL 与结果转换仍归各自模块。

```sh
make go-backend-test       # gofmt/vet/staticcheck/govulncheck/race
make web-check db-sqlite-check
make go-mysql-check       # 随机隔离 MySQL 容器
make go-recovery-check    # 原生二进制真实 PTY 账号恢复、独立临时数据
make go-container-check   # 已构建镜像、随机独占容器/网络、不读取现有卷
make docker-context-check # 实际 COPY 构建上下文排除规则，不读取部署卷
make go-compute-check     # 项目 Python/FFmpeg 真实往返
make backend-check        # Go + Python 检查及真实联动
```

`internal/library` 测试覆盖来源/规则/扫描原子发布、WebDAV 范围与本地下载并发；`internal/metadata` 覆盖人工字段保护、平台/封面安全、有限回源授权、任务恢复、共享分片检查点与导入收据。其他模块覆盖认证、TV/H5、分类、队列、AI、下载、播放、缓存及持久后台投影。具体实测与限制见 [Go 验证记录](../../docs/go-migration-verification.md)。

`go-container-check` 默认检查 `singverse-go-backend:local` 与 `singverse-frontend:local`，可用 `GO_BACKEND_IMAGE`、`GO_FRONTEND_IMAGE` 指定已构建的架构镜像；不会拉取或构建镜像。恢复检查拒绝运行中部署锁和非 TTY 输入，验证口令隐藏、会话撤销、MFA 清除及业务数据/主密钥保留。

生产默认 Dockerfile 为 `infra/docker/build/go.Dockerfile`，Compose 为 `infra/docker/compose/compose.app.yaml`，项目名 `singverse-go-app` 使用全新卷。采用全新部署，不迁移旧 Java 账号或库，不允许两种实现共享写入后的数据库。旧数据保护与明确回退见 [迁移说明](../../docs/go-migration.md)。本机管理员恢复为停止服务后 `bin/singverse --reset-admin`，需真实终端，见 [认证说明](../../docs/authentication.md)。
