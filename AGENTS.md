# SingVerse 协作指南

适用于整个仓库；修改前检查子目录是否有更具体的 `AGENTS.md`。默认简体中文，代码标识符沿用模块风格。

## 项目与范围

- 家庭自托管 KTV，使用用户自备歌曲。当前有多端欢迎页、Vue 管理概览/健康查询、Java 数据库连接检查、Python 探测骨架及本地 Docker 交付；已实现单管理员登录、改密、可选 TOTP MFA、媒体来源与解析方案、歌曲管理、本地目录及 WebDAV 文件名扫描及歌曲元数据保存、平台元数据刮削与审核；已支持管理端本地/WebDAV 原始音视频在线播放；已新增 TV 设备授权、聚会队列和亲友 H5 扫码点歌，以及随曲库自动生成的歌手库、首次自动抓图、图片上传及 NAS 歌手订正、共享歌单管理和 AI 歌单建议；已新增 compute 实时 HLS/离线兼容版本及 Java 授权编排；已增加按素材试跑的硬解/零拷贝与 GPU 色调映射，边界见 docs/transcoding.md，真机验收见 docs/verification.md。
- 亲友入口为微信扫一扫普通 NAS URL 打开 H5；不恢复小程序/Taro。`docs/prototype/`、`docs/kimi-prototype/` 是设计原型，不是生产功能。
- 按需阅读根及目标模块 README；架构见 [docs/architecture.md](docs/architecture.md)，开发见 [docs/development.md](docs/development.md)。涉及产品行为再查 [PRD](docs/research/PRD.md)，候选需求不自动成为开发范围。事实以源码、配置和本次验证为准。

## 目录与职责

应用工程统一位于 `services/`，使用既定名称，保留框架标准内部结构；详细布局见 [docs/repository-layout.md](docs/repository-layout.md)。

| 工程 | 职责 |
|---|---|
| `services/singverse-frontend/` | Vue / TypeScript / Vite / Element Plus；管理与亲友 H5 共用一个工程和完整 dist |
| `services/singverse-java-backend/` | Java 21 / Spring Boot；业务授权、曲库、会话、队列和唯一业务数据库写入 |
| `services/singverse-compute/` | Python / FastAPI；内部重计算执行层，健康/能力探测及只读音视频标签读取 |
| `services/singverse-mobile/` | Flutter Android；原生本机 K 歌，扫码电视会话复用 NAS H5，当前无 iOS 工程 |
| `services/singverse-tv/` | Kotlin / Compose TV；遥控器交互及实际播放状态回报 |

- Java 负责计算任务编排与结果入库；服务端媒体探测、转解码和 AI 重计算归 Python。Python 不写业务库、不管理队列，客户端不直连 Python/数据库；本机采集、监听与录音留在设备端。
- Python 不对应独立业务产品；系统设置的性能节点配置仅供管理员管理内部连接，支持部署自动识别或独立部署配置，客户端仍只调用 Java。AUTO模式且`COMPUTE_NODE_URL`为空时关闭探测，可选节点不加入主服务健康依赖；空能力列表不代表可执行媒体任务。使用项目 `.venv` 和 `requirements.lock`。
- 不预建 Go、微服务、Redis 或消息队列。未来 Go 复用静态产物与语言无关契约，约束见 [docs/go-migration.md](docs/go-migration.md)。

## 前端、API 与数据

- 管理页：Nginx 对外 `/`，兼容 `/admin/`；Java 直接托管 `/admin/`。亲友 H5 固定 `/party/`，管理使用 hash 路由。前端仅调用同源 `/api`，代理保留前缀；托管规则见 [contracts/frontend-hosting.md](contracts/frontend-hosting.md)。
- Java 打包前需构建完整前端；根 Make 目标自动处理，直接 Maven 前执行 `make frontend-build`。缺少产物必须失败。保留 npm 锁文件，使用 `npm ci`；产物保持独立于 Java 类型，生产无需 Node 进程。
- 当前健康为 `/api/health`，保留 Actuator 原生响应且不公开数据库详情。新业务 API 使用 `/api/v1`，遵循 [API 约定](contracts/openapi/README.md)；账号契约在 `contracts/openapi/singverse.openapi.json`，只为真实功能扩展接口。
- Java 数据访问统一使用 MyBatis；所有业务查询及写入 SQL 必须显式写在 `services/singverse-java-backend/src/main/resources/mappers/**/*.xml`，Mapper 接口只声明方法与参数。禁止在 Java 中拼接/内联业务 SQL、使用 `JdbcTemplate` 执行业务 SQL，或使用 MyBatis SQL 注解、Provider 隐藏 SQL。参数使用 `#{}` 绑定，禁止用 `${}` 拼接外部输入；事务继续由 Spring 管理。此规则适用于整个仓库后续新增与修改的 Java 业务持久化代码。dbmate 的 DDL/数据迁移保留在 `database/migrations/`；连接池健康探针及隔离测试的数据准备/独立断言允许使用固定 JDBC SQL，不得借此绕过业务 XML 规则。
- 默认 SQLite，路径相对服务工作目录，保留父目录创建与单连接池；MySQL 为可选 Profile，两者互斥，失败不静默回退。切换不迁移数据，连接成功不代表业务兼容；账号表已使用 `database/migrations/` 的 dbmate SQL 迁移，见 [数据库策略](docs/database-strategy.md)。
- `compose.app.yaml` 为 Nginx + Java + SQLite 卷，`compose.yaml` 独立提供开发 MySQL。Dockerfile 与 Nginx 配置归 Java 后端目录；运行及端口见 [docs/docker.md](docs/docker.md)。Spring Boot 不自动加载根 `.env`。

- 单管理员 `admin`，初始密码 `admin` 仅提示修改，不做多账号；MFA 为 RFC6238 TOTP。会话、PHC 哈希、密钥格式与本机恢复见 [认证说明](docs/authentication.md)，不得引入 Java 序列化凭据或网络重置后门。

## 修改与保护

- 只做任务所需修改，保留未提交工作、本地配置和锁文件；不顺带升级版本或修改全局工具环境。Java/Gradle 使用仓库 Wrapper，遵循 `.editorconfig`，Dart 使用 `dart format` 与现有 lint。
- 目录、命令、环境变量、接口或行为变化时同步 README、相关 docs、Makefile 和 CI；详细版本及安装步骤留在模块文档，不在此重复。
- 不提交或输出 `.env`、凭据、签名密钥、SDK 本地路径、数据库、日志和构建产物；示例使用占位值。保留命名卷，不擅自 `down -v`、删库或重置账号；媒体原件默认只读，缓存/衍生文件分开。
- 默认仅本机/受限网络访问，不为连通性全局关闭证书验证或默认暴露公网。跨主机计算节点先落实认证、TLS/受限网络与媒体权限。手机/二维码使用手机可达的 NAS 地址，不用 localhost 或容器 IP。
- TV 修改检查遥控焦点和大屏可读性；当前 TV minSdk 23、手机 minSdk 24，Android 5/API 21 仅为目标，不能简单下调配置声称兼容。

## 验证与交付

从根目录按改动选择检查，不重复完整构建无关模块；完整命令说明以 `make help` 和开发文档为准。

| 改动 | 检查入口 |
|---|---|
| 前端/H5 | `make frontend-check`；托管与资源 `make web-check` |
| Java | `make java-backend-test` |
| Python / Java-Python 联动 | 首次 `make compute-setup`；`make compute-check` / `make backend-check` |
| SQLite / MySQL | `make db-sqlite-check` / 配置真实 MySQL 后 `make db-mysql-up db-mysql-check` |
| 手机 / TV | `make mobile-check`，平台构建 `make mobile-build` / `make tv-build` |
| Docker | `make docker-up`、`make docker-check`；停止用 `make docker-down`，保留数据卷 |

- 业务变更/修复补充实际行为测试；HTTP/数据库测试使用随机端口和隔离数据。纯文档检查路径、命令及事实即可；不得跳过断言来掩盖失败。
- 交付简述改动、实际检查和剩余限制。构建、浏览器、本地 Docker 不能代替微信/TV/NAS 真机或其他 CPU 架构验收，CI 配置不代表远程通过；需要记录时更新 [docs/verification.md](docs/verification.md)，不复用旧结果冒充本次验证。
