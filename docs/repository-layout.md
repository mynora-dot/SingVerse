# 仓库布局

应用工程统一位于 `services/`，对外协议与数据迁移独立于实现语言。

| 目录 | 职责 |
|---|---|
| `services/singverse-go-backend/` | Go 唯一生产业务主服务，授权、曲库、队列、任务和数据库写入 |
| `services/singverse-frontend/` | Vue/TypeScript 管理页与亲友 H5，同一完整 `dist` |
| `services/singverse-compute/` | Python 内部媒体/AI 执行层，不写业务库 |
| `services/singverse-mobile/` | Flutter Android 本机 K 歌与扫码 H5 |
| `services/singverse-tv/` | Kotlin/Compose TV，遥控交互与真实播放 |
| `contracts/` | OpenAPI、前端托管、认证、缓存、后台任务等语言无关契约 |
| `database/migrations/{sqlite,mysql}/` | dbmate 初始化和增量迁移 |
| `infra/docker/compose/` | 默认 Go 应用、独立计算、硬件覆盖、开发 MySQL 及明确旧回退入口 |
| `infra/docker/build/` | Go Dockerfile、构建忽略规则、Nginx 和计算镜像矩阵 |
| `infra/deploy/` | 业务/计算节点部署包模板 |
| `scripts/` | 构建、检查、固定工具安装与部署包生成 |
| `docs/` | 当前说明与带日期的历史验证；原型/research 不代表已交付功能 |

## Go 内部结构

```text
services/singverse-go-backend/
  cmd/singverse/       启动、信号退出、本机恢复及隔离 LX worker 入口
  internal/app/        显式依赖组装、HTTP 路由、安全边界与进程诊断
  internal/platform/  配置、SQL/迁移、HTTP、测试设施
  internal/auth/      单管理员、Cookie/CSRF、MFA 与密钥
  internal/library/   来源、规则、扫描、歌曲与原片
  internal/metastandard/ 公开 Schema 1.0、严格 JSON/JCS 和独立校验规则
  internal/metastore/ 正式文档、字段决定、证据、位置与原始附件
  internal/metadata/  平台刮削、审核、文件标签、共享包与持久衍生文件
  cmd/metadata-validate/ 不依赖数据库或 compute 的资料格式校验器
  internal/catalog/   分类、歌手与歌单
  internal/party/     TV、亲友 H5、移动授权、队列与曲库索引
  internal/compute/   内部节点配置、配对、认证和受控请求
  internal/execution/ 任务执行协调、转码、音轨和缓存
  internal/playback/  播放实例及活动统计
  internal/feedback/  缺歌反馈
  internal/background/统一任务与批次投影
  internal/ai/        管理配置及受控 AI 调用
  internal/downloads/ 自备音源、搜索与持久下载发布
  internal/web/       同源完整静态产物托管
```

业务 SQL 位于各模块 `sql/*.sql`，通过 `go:embed` 加载；模块之间共享 `store.DBTX` 参与调用方事务。Gin 类型留在 HTTP 适配层，文件/网络和数据库生命周期明确分离。详细约定见 [Go 模块 README](../services/singverse-go-backend/README.md)。

前端构建产物仍为 `services/singverse-frontend/dist`，`prepare-go-assets.py` 将完整产物复制到被忽略的 `internal/web/dist` 供嵌入；源码不依赖服务端模板。Docker 构建也从同一前端阶段复制完整 dist，生产不运行 Node。

Makefile 和 CI 保留根及 `.github/workflows/` 标准位置。当前后端检查为 `singverse-go-backend`、`singverse-backend` 与 `singverse-docker`，Java 源码和 Maven 检查工作流已退休。所有新增容器资源放 `infra/`，不散放服务目录。现有 compute Dockerfile/专用资源保持原路径；移动与 TV 的包名、application ID 和 Wrapper 不因业务语言替换变化。

旧 Java 目录可能仍保留被忽略的本地数据与配置，不再包含受控工程；旧路径只在历史验证或明确镜像回退说明中保留。不会为了路径调整重置账号、覆盖配置或删除数据卷，详见 [切换与回退](go-migration.md)。
