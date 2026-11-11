# 开发与验证

业务主服务位于 `services/singverse-go-backend/`，使用 Go 1.27.4、Gin、`database/sql + sqlx` 与嵌入 SQL。前端仍为独立 Vue/Vite 工程，计算执行仍为 Python。Go 依赖由 `go.mod`/`go.sum` 锁定；工具和缓存留在仓库 `.tools/`，不改全局环境。Android 的 JDK/Gradle 要求与业务服务独立。

Go 1.27.4 修复标准库公告 GO-2026-6859；本地脚本、CI 和 Docker 构建保持同一补丁版本，避免旧工具链使 `govulncheck` 门禁失败。

## 本地启动

从仓库根执行：

```sh
make frontend-setup
make go-tools-setup dbmate-setup
make go-backend-build
make go-backend-run
# 可选：另一个终端运行 Vite，代理到已启动的业务服务
make frontend-dev
```

原生 Go 默认监听 `127.0.0.1:8080`；管理入口为 `/` 或 `/admin/`，亲友入口为 `/party/`，健康为 `/api/health`。`make go-backend-run` 在模块目录运行，默认数据目录为 `services/singverse-go-backend/data/`。Go 不自动加载 `.env`，需要显式导出变量；示例见根 `.env.example`。SQLite 路径相对进程工作目录，避免复用旧 Java 数据目录。

`make go-backend-assets` 先构建前端，再复制整个 `dist` 到被忽略的 `internal/web/dist/`。构建用 `go:embed` 嵌入两个页面及全部资源；缺少任一入口时明确失败。前端变更后重新构建二进制。生产无需 Node、Go 编译器或 JVM。

## 检查入口

| 内容 | 根命令 |
|---|---|
| Go 格式、vet、staticcheck、govulncheck、race 全部业务测试 | `make go-backend-test` |
| 真实终端管理员恢复，独立临时数据 | `make go-recovery-check` |
| 已构建 Go/Nginx 镜像的隔离容器验收 | `make go-container-check`，可指定 `GO_BACKEND_IMAGE` / `GO_FRONTEND_IMAGE` |
| 独立静态托管与权限边界 | `make web-check` |
| 空库迁移、幂等迁移和回退重建 | `make db-fresh-check` |
| SQLite 事务、进程锁及业务 HTTP | `make db-sqlite-check` |
| 真实隔离 MySQL 业务测试 | `make go-mysql-check` 或 `make db-mysql-check` |
| Python 全部 HTTP/内部协议测试 | `make compute-check` |
| 真实 Go/Python/FFmpeg 往返 | `make go-compute-check` |
| Go 检查、Python 检查及真实联动 | `make backend-check` |
| 前端类型与构建；交互测试 | `make frontend-check`；模块内 `npm test` |
| 容器配置；真实页面/代理；隔离 MFA 恢复 | `make docker-config-check`；`make docker-check`；`make auth-docker-check` |

Go 单项检查可通过 `sh scripts/run-go.sh test ./internal/library -run TestLocal` 运行，复用仓库缓存。数据库和 HTTP 测试用临时目录、随机回环端口，不重置开发数据。MySQL 检查脚本自行创建随机、仅回环发布的容器，只清理该测试容器。

Python 首次使用执行 `make compute-setup PYTHON=python3.12`；FFmpeg/ffprobe 用 `make transcode-tools` 安装固定工具到 `.tools`。真实联动缺虚拟环境或工具时失败，不用模拟成功替代。可选音频模型和 GPU 运行库分别按 [转码说明](transcoding.md) 与 [GPU 指南](../infra/docker/GPU.md) 安装。

## 模块约定

核心阅读入口及文件职责见 [Go 工程说明](../services/singverse-go-backend/README.md#代码约定与验证)。路由注册按业务分组并使用有语义的处理函数名；长流程按校验、业务变更、投影与提交步骤拆分。局部变量表达具体角色，注释解释幂等、授权或事务顺序。聚会的 `runAsActor` 在操作事务内完成身份校验，不能替换成另取连接的认证步骤。

HTTP 注册、DTO 解码、Cookie/Bearer 与错误映射留在 HTTP 边界；业务用 `context.Context` 和显式服务依赖。数据库连接与事务统一为 `*sqlx.DB` / `*sqlx.Tx`，向跨模块调用传递 `store.DBTX`，SQLite 单连接事务内不得另取连接。固定结果使用独立行模型及显式 `db` 标签，通过 `GetContext` / `SelectContext` / `StructScan` 读取；通用投影复用 `store.Maps` 的 sqlx 映射和文本/二进制归一化。业务 SQL 必须放在 `internal/<module>/sql/*.sql`，通过嵌入查询和绑定参数执行，`@{name}` 标记由平台适配到 sqlx 命名参数；不引入 ORM，不拼接用户输入。结构变更增加 dbmate 迁移，禁止修改已部署基线。

后台任务使用有界 worker、持久状态、上下文取消和关闭等待；发布索引与任务状态在同一事务提交，外部 HTTP/文件遍历不占长事务。本地扫描与下载通过来源级发布锁协调。重启恢复、取消、人工字段保护和文件删除日志应有实际行为断言。

日志使用 `slog` JSON，HTTP 关联 `X-Request-ID`，不得记录凭据、媒体签名 URL 或 SQL 参数。可选 `SINGVERSE_DIAGNOSTICS_ADDRESS` 只能显式绑定回环，诊断不挂到公开业务路由。`/api/health` 只公开整体状态，计算节点离线不拖垮可直放业务。

## CI 与设备验证

`singverse-go-backend` 验证静态分析、漏洞、race、SQLite/MySQL 并构建版本化二进制；`singverse-backend` 验证真实 Python 联动；`singverse-docker` 验证容器矩阵、部署包、代理流式传输及登录恢复。Java 源码及 Maven 工作流已退休，镜像回退不要求安装宿主 JDK。配置存在不代表远程 CI 已通过。

Flutter/TV 保留现有构建：`make mobile-check mobile-build`、`make tv-build`；JDK 仍供 Android 工程使用。TV minSdk 23、手机 minSdk 24；微信、真实麦克风、电视遥控焦点、NAS 权限及各 CPU/GPU 架构必须单独验收。历史结果不改写为 Go 本次结果，见 [Go 验证](go-migration-verification.md) 和 [验证记录](verification.md)。
