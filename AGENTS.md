# SingVerse 协作指南

适用于整个仓库。修改前检查目标目录是否有更具体的 `AGENTS.md`；默认使用简体中文，代码标识符沿用模块风格。

## 开始工作

- SingVerse 是使用用户自备歌曲的家庭自托管 KTV，包含 NAS 管理、手机本机 K 歌、电视播放及亲友 H5 点歌。
- 先查看工作区差异，保留用户已有修改、本地配置及锁文件；只处理本次任务，不顺带升级依赖或版本。
- 按需阅读根 README、目标模块 README、[架构](docs/architecture.md)和[开发说明](docs/development.md)。涉及产品范围时参考 [PRD](docs/research/PRD.md)，候选需求不自动成为开发范围。
- 当前行为以生产源码、配置和本次验证为准。原型和历史截图已清理，不恢复旧原型，不用历史验收代替当前验证。
- 项目技能见 [skills/README.md](skills/README.md)，按任务选用，不要求每次加载全部技能。

## 工程职责

应用统一位于 `services/`，保留既定模块名称及框架标准结构。

| 工程 | 职责 |
|---|---|
| `singverse-frontend` | Vue / TypeScript / Vite / Element Plus；NAS 管理与亲友 H5，共用一个完整 dist |
| `singverse-go-backend` | Go / Gin / database/sql + sqlx；唯一生产业务主服务及业务数据库写入方 |
| `singverse-compute` | Python / FastAPI；内部媒体探测、转解码及 AI 重计算执行层 |
| `singverse-mobile` | Flutter Android；本机采集、监听、播放及录音，当前没有 iOS 工程 |
| `singverse-tv` | Kotlin / Compose TV；遥控器交互、实际播放及状态回报 |

- Go 负责授权、曲库、会话、队列、计算编排和结果入库。Python 不写业务库、不管理业务队列；客户端只调用 Go，不直连 Python 或数据库。
- 计算节点是可选内部能力，不是独立业务产品。AUTO 模式且 `COMPUTE_NODE_URL` 为空时关闭探测；节点不加入主服务健康依赖，空能力列表不代表任务可执行。
- 不预建微服务、Redis 或消息队列。亲友入口固定为微信扫一扫普通 NAS URL 打开 H5，不恢复小程序或 Taro。
- 手机及二维码必须使用手机可达的 NAS 地址，不使用 localhost 或容器 IP。TV 修改需检查遥控焦点和大屏可读性；当前 TV minSdk 23、手机 minSdk 24，不通过下调配置声称兼容 Android 5/API 21。

## 前端与接口

- 正式 GitHub Release 才发布镜像；三平台命名空间和阿里云域名使用 Actions Variables，凭据使用 Secrets。版本标签不得覆盖为不同内容，所有固定版本通过后再更新 latest；未经发布任务授权不推送。
- 管理入口为 `/`，兼容 `/admin/`，管理使用 hash 路由；亲友入口固定 `/party/`。前端只调用同源 `/api`，代理保留前缀，遵循[托管契约](contracts/frontend-hosting.md)。
- Go 构建前准备完整前端：`make go-backend-assets` 构建并复制整个 dist，随后嵌入二进制；缺少必要产物必须失败。使用 npm 锁文件和 `npm ci`，生产业务容器不依赖 Node 或 JVM。
- 健康接口为 `/api/health`，保留 `{"status":"UP"}` / DOWN 兼容响应，不公开数据库详情。业务 API 使用 `/api/v1`，遵循 [API 约定](contracts/openapi/README.md)，同步维护真实功能对应的 OpenAPI 契约。
- 单管理员，不扩展多账号；初始账号密码为 `admin/admin`，默认密码仅提示修改。可选 MFA 使用 RFC6238 TOTP；凭据、会话和本机恢复遵循[认证说明](docs/authentication.md)，不引入网络重置后门或 Java 序列化凭据。
- 版本只从根 `VERSION` 注入，不在模块或脚本中维护另一份产品版本。

## 数据与事务

- 使用 `database/sql + sqlx` 和显式嵌入 SQL，不引入 ORM。业务 SQL 放在所属 `internal/<domain>/sql/*.sql`，使用 `?` 或平台 `@{name}` 绑定，不拼接外部输入。
- 连接与事务使用 `*sqlx.DB` / `*sqlx.Tx`，通过 `store.DBTX` 传递；事务内跨模块调用必须复用执行器，不能另取连接。
- 固定投影使用独立数据库行模型、显式 `db` 标签和带 context 的 `Get` / `Select` / `StructScan`；通用投影走 `store.Maps`，保留文本、二进制和空值语义。健康检查及隔离测试准备允许固定 SQL。
- Gin 只处理 HTTP 边界；模块间使用 context、领域类型和 net/http 接口协作。网络请求和媒体计算不应占用业务数据库事务。
- 默认 SQLite，路径相对服务工作目录，保留父目录创建及单连接池。MySQL 仅由 `SINGVERSE_DB_DRIVER=mysql` 显式选择，失败不静默回退，切换不自动迁移数据。
- dbmate 迁移位于 `database/migrations/{sqlite,mysql}/`，以各自的 `202611070001_initial_schema.sql` 从空库初始化；后续新增迁移，不修改已部署基线，不提供旧迁移链升级路径。详见[数据库说明](database/README.md)与[数据库策略](docs/database-strategy.md)。

## 构建、脚本与部署

- 根 Makefile 是常用命令入口，具体命令见 `make help`。`scripts/` 放本机构建、检查、工具安装与模型工具；Go 脚本统一复用 `scripts/go-env.sh` 的工具链和仓库缓存。
- Python 使用工程内 `.venv`、`requirements.lock` 及对应可选运行库锁文件；Gradle 使用仓库 Wrapper；Dart 遵循 `dart format` 和已有 lint。不修改全局工具环境。
- Docker 相关资源统一归入 `infra/`：Compose 放 `infra/docker/compose/`；业务与 compute Dockerfile、配套资源和忽略规则放 `infra/docker/build/`；Docker 检查脚本放 `infra/docker/`。仅提供源码构建与预构建镜像两条路线，不恢复旧部署包及离线 ZIP。
- 默认 `compose.yaml` 只启动内置前端的 Go 业务容器，compute 通过 profile 显式启用；源码路线追加 `compose.build.yaml`，镜像路线不含 build。业务项目名和持久卷身份保留，开发 MySQL 使用 `compose.mysql.yaml`。原生 Go 不自动加载根 `.env`。
- 根 `.dockerignore` 是 Docker 标准兼容入口，必须与维护源 `infra/docker/build/.dockerignore` 字节一致；Go 专用规则也在该目录。不通过父目录重包含放行缓存、数据或凭据。
- 迁移目录或脚本时同步 Makefile、CI、测试及文档引用；验证构建上下文、根 `.env` 读取、相对挂载、项目名和命名卷。布局见[仓库布局](docs/repository-layout.md)，部署见 [Docker 指南](infra/docker/README.md)。

## 数据与环境保护

- 不提交或输出 `.env`、凭据、签名密钥、SDK 本地路径、数据库、日志及构建产物；示例使用占位值。
- 保留命名卷，不擅自执行 `down -v`、删库或重置账号。媒体原件默认只读，缓存和衍生文件单独存放。
- 默认仅本机或受限网络访问，不为连通性全局关闭证书验证或默认暴露公网。跨主机计算先落实认证、TLS 或受限网络及媒体权限。
- Java 源码、Maven 工作流及旧 Docker 回退入口已退休，不恢复 JVM 生产链路。旧命名卷保持原状，不把 Go 数据库挂给 Java。新 Go 部署不迁移旧 Java 账号或数据库。Docker 镜像回退需匹配的数据及密钥备份，详见[运维](infra/docker/OPERATIONS.md)。

## 验证与交付

按改动范围选择检查，不重复构建无关模块。业务变更补充实际行为测试；HTTP 和数据库测试使用随机端口及隔离数据，不跳过断言掩盖失败。

| 改动 | 检查入口 |
|---|---|
| 前端 / H5 | `make frontend-check`；交互测试在前端模块执行 `npm test`；托管与资源用 `make web-check` |
| Go | 首次 `make go-tools-setup dbmate-setup`；执行 `make go-backend-test` |
| Python / Go-Python | 首次 `make compute-setup`；执行 `make compute-check` / `make backend-check`，真实联动需准备 FFmpeg |
| SQLite / MySQL | `make db-fresh-check`、`make db-sqlite-check` / `make db-mysql-check`；MySQL 检查自行创建隔离容器 |
| 手机 / TV | `make mobile-check`；平台构建 `make mobile-build` / `make tv-build` |
| Docker 配置 / 发布 | `make docker-config-check docker-context-check docker-test`；三平台发布配置见 `infra/docker/PUBLISHING.md` |
| Docker 运行 | `make docker-up`、`make docker-check`；停止用 `make docker-down`，保留数据卷 |

纯文档修改检查链接、命令和事实即可。交付说明实际改动、已执行检查及剩余限制；构建、浏览器和本地 Docker 不代替微信、TV、NAS 或其他 CPU/GPU 架构真机验收，CI 配置不代表远程通过。需要留档时更新[验证记录](docs/verification.md)，不复用旧结果冒充本次验证。
