# SingVerse 协作指南

适用于整个仓库；修改前检查子目录是否有更具体的 `AGENTS.md`。默认简体中文，代码标识符沿用模块风格。

## 项目与范围

- 家庭自托管 KTV，使用用户自备歌曲。当前有多端欢迎页、Vue 管理概览/健康查询、Go 数据库连接检查、Python 探测骨架及本地 Docker 交付；已实现单管理员登录、改密、可选 TOTP MFA、媒体来源与解析方案、歌曲管理、本地目录及 WebDAV 文件名扫描及歌曲元数据保存、平台元数据刮削与审核；已支持管理端本地/WebDAV 原始音视频在线播放；已新增 TV 设备授权、聚会队列和亲友 H5 扫码点歌，以及随曲库自动生成的歌手库、首次自动抓图、图片上传及 NAS 歌手订正、共享歌单管理和 AI 歌单建议；已新增 compute 实时 HLS/离线兼容版本及 Go 授权编排；已增加按素材试跑的硬解/零拷贝与 GPU 色调映射，边界见 docs/transcoding.md，真机验收见 docs/verification.md。
- 亲友入口为微信扫一扫普通 NAS URL 打开 H5；不恢复小程序/Taro。`docs/prototype/`、`docs/kimi-prototype/` 是设计原型，不是生产功能。
- 按需阅读根及目标模块 README；架构见 [docs/architecture.md](docs/architecture.md)，开发见 [docs/development.md](docs/development.md)。涉及产品行为再查 [PRD](docs/research/PRD.md)，候选需求不自动成为开发范围。事实以源码、配置和本次验证为准。

## 目录与职责

应用工程统一位于 `services/`，使用既定名称，保留框架标准内部结构；详细布局见 [docs/repository-layout.md](docs/repository-layout.md)。

| 工程 | 职责 |
|---|---|
| `services/singverse-frontend/` | Vue / TypeScript / Vite / Element Plus；管理与亲友 H5 共用一个工程和完整 dist |
| `services/singverse-go-backend/` | Go / Gin / database/sql + sqlx；业务授权、曲库、会话、队列和唯一业务数据库写入 |
| `services/singverse-compute/` | Python / FastAPI；内部重计算执行层，健康/能力探测及只读音视频标签读取 |
| `services/singverse-mobile/` | Flutter Android；原生本机 K 歌，扫码电视会话复用 NAS H5，当前无 iOS 工程 |
| `services/singverse-tv/` | Kotlin / Compose TV；遥控器交互及实际播放状态回报 |

- Go 负责计算任务编排与结果入库；服务端媒体探测、转解码和 AI 重计算归 Python。Python 不写业务库、不管理队列，客户端不直连 Python/数据库；本机采集、监听与录音留在设备端。
- Python 不对应独立业务产品；系统设置的性能节点配置仅供管理员管理内部连接，支持部署自动识别或独立部署配置，客户端仍只调用 Go。AUTO模式且`COMPUTE_NODE_URL`为空时关闭探测，可选节点不加入主服务健康依赖；空能力列表不代表可执行媒体任务。使用项目 `.venv` 和 `requirements.lock`。
- Go 为唯一生产业务主服务，不预建微服务、Redis 或消息队列。保留客户端/Python HTTP 契约，切换与旧服务回退约束见 [docs/go-migration.md](docs/go-migration.md)。

## 前端、API 与数据

- 管理页：Nginx 对外 `/`，兼容 `/admin/`；Go 直接托管 `/admin/`。亲友 H5 固定 `/party/`，管理使用 hash 路由。前端仅调用同源 `/api`，代理保留前缀；托管规则见 [contracts/frontend-hosting.md](contracts/frontend-hosting.md)。
- Go 构建前需构建完整前端；根 Make 目标执行 `make go-backend-assets` 后嵌入整个 dist，缺少产物必须失败。保留 npm 锁文件，使用 `npm ci`；生产业务容器无需 Node/JVM。版本只从根 `VERSION` 注入。
- 当前健康为 `/api/health`，保留兼容的 `{"status":"UP"}` / DOWN 响应且不公开数据库详情。新业务 API 使用 `/api/v1`，遵循 [API 约定](contracts/openapi/README.md)；账号契约在 `contracts/openapi/singverse.openapi.json`，只为真实功能扩展接口。
- Go 数据访问统一使用 `database/sql + sqlx` 与显式嵌入 SQL；业务 SQL 放在所属 `internal/<domain>/sql/*.sql`，使用 `?` 或平台 `@{name}` 绑定，不拼接外部输入，不引入 ORM。连接与事务使用 `*sqlx.DB` / `*sqlx.Tx`，通过 `store.DBTX` 传递；事务内调用其他模块必须复用执行器，禁止另取连接。固定结构使用独立数据库行模型及显式 `db` 标签，通过带 context 的 `Get` / `Select` / `StructScan` 读取；通用投影统一走 `store.Maps`，保留文本、二进制和空值语义。DDL/dbmate 迁移仍在 `database/migrations/`；健康固定 SQL 及隔离测试数据准备允许固定 SQL。Gin 只处理 HTTP 边界，模块之间通过 context、领域类型和 net/http 接口协作。
- 默认 SQLite，路径相对服务工作目录，保留父目录创建与单连接池；MySQL 由 `SINGVERSE_DB_DRIVER=mysql` 显式选择，两者互斥，失败不静默回退。切换不迁移数据，连接成功不代表业务兼容。当前两种数据库各以 `database/migrations/{sqlite,mysql}/202611070001_initial_schema.sql` 从空库初始化，不提供旧迁移链升级路径；后续在此基线上新增迁移，不修改已部署的基线。见[数据库说明](database/README.md)及[数据库策略](docs/database-strategy.md)。
- `infra/docker/compose/compose.app.yaml` 为 Nginx + Go + compute，项目名 `singverse-go-app` 使用全新独立卷，`infra/docker/compose/compose.yaml` 独立提供开发 MySQL。业务 Dockerfile 位于 `infra/docker/build/go.Dockerfile`，Nginx 配置位于 `infra/docker/build/nginx/`；运行及端口见 [docs/docker.md](docs/docker.md)。Go 原生进程不自动加载根 `.env`。
- 后续新增 Docker 相关文件统一放在 `infra/` 下分类管理，不再散放于仓库根目录或应用工程：Compose 主配置及覆盖文件放 `infra/docker/compose/`，Dockerfile、Bake、构建忽略规则及其他构建配置放 `infra/docker/build/`，部署包模板放 `infra/deploy/`，Docker 专用脚本及配套资源也归入 `infra/` 对应分类。已有文件需要迁移时同步更新 Makefile、CI、脚本与文档引用，并验证构建上下文、根 `.env` 读取、相对挂载路径、项目名及命名卷保持预期；Makefile 和 CI 工作流保留标准位置并引用 `infra/` 中的文件。
- 根 `.dockerignore` 仅为 Docker 标准兼容入口，维护源仍为 `infra/docker/build/.dockerignore`，配置检查强制两者字节一致；Go 专用规则同样位于 `infra/docker/build/`。源码部署包从 infra 注入包根忽略规则，禁止用父目录重包含放行缓存、数据或凭据。

- 全新 Go 部署不迁移已有 Java 数据库/账号。Java 受控源码及 Maven 工作流已在本轮后端与交付验收后退休，回退仅使用已保存镜像；旧本地数据、配置和 `singverse-app_*` 卷保持原状；不向 Java 挂载 Go 已写入的库，也不承诺两者共享数据库。生产默认流程不可调用 JVM。
- 单管理员，初始登录账号 `admin`（可修改），初始密码 `admin` 仅提示修改，不做多账号；MFA 为 RFC6238 TOTP。会话、PHC 哈希、密钥格式与本机恢复见 [认证说明](docs/authentication.md)，不得引入 Java 序列化凭据或网络重置后门。

## 修改与保护

- 只做任务所需修改，保留未提交工作、本地配置和锁文件；不顺带升级版本或修改全局工具环境。Go 使用模块锁定依赖及仓库内工具缓存；Gradle 使用仓库 Wrapper，遵循 `.editorconfig`，Dart 使用 `dart format` 与现有 lint。
- 目录、命令、环境变量、接口或行为变化时同步 README、相关 docs、Makefile 和 CI；详细版本及安装步骤留在模块文档，不在此重复。
- 不提交或输出 `.env`、凭据、签名密钥、SDK 本地路径、数据库、日志和构建产物；示例使用占位值。保留命名卷，不擅自 `down -v`、删库或重置账号；媒体原件默认只读，缓存/衍生文件分开。
- 默认仅本机/受限网络访问，不为连通性全局关闭证书验证或默认暴露公网。跨主机计算节点先落实认证、TLS/受限网络与媒体权限。手机/二维码使用手机可达的 NAS 地址，不用 localhost 或容器 IP。
- TV 修改检查遥控焦点和大屏可读性；当前 TV minSdk 23、手机 minSdk 24，Android 5/API 21 仅为目标，不能简单下调配置声称兼容。

## 验证与交付

从根目录按改动选择检查，不重复完整构建无关模块；完整命令说明以 `make help` 和开发文档为准。

| 改动 | 检查入口 |
|---|---|
| 前端/H5 | `make frontend-check`；托管与资源 `make web-check` |
| Go | 首次 `make go-tools-setup`；`make go-backend-test` |
| Python / Go-Python 联动 | 首次 `make compute-setup`；`make compute-check` / `make backend-check` |
| SQLite / MySQL | `make db-fresh-check`；`make db-sqlite-check` / `make db-mysql-check` 自动创建随机隔离 MySQL 容器 |
| 手机 / TV | `make mobile-check`，平台构建 `make mobile-build` / `make tv-build` |
| Docker | `make docker-up`、`make docker-check`；停止用 `make docker-down`，保留数据卷 |

- 业务变更/修复补充实际行为测试；HTTP/数据库测试使用随机端口和隔离数据。纯文档检查路径、命令及事实即可；不得跳过断言来掩盖失败。
- 交付简述改动、实际检查和剩余限制。构建、浏览器、本地 Docker 不能代替微信/TV/NAS 真机或其他 CPU 架构验收，CI 配置不代表远程通过；需要记录时更新 [docs/verification.md](docs/verification.md)，不复用旧结果冒充本次验证。
