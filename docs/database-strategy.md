# 数据库策略

## 从空库初始化

当前面向首次从零部署。SQLite 和 MySQL 各保留一份 dbmate 初始化迁移：

- [SQLite：202611070001_initial_schema.sql](../database/migrations/sqlite/202611070001_initial_schema.sql)
- [MySQL：202611070001_initial_schema.sql](../database/migrations/mysql/202611070001_initial_schema.sql)

初始化脚本直接定义最终列、约束、索引和种子数据，不再依次执行开发阶段的历史 ALTER、数据回填或重建表。业务结构覆盖认证与会话、媒体来源与扫描索引、元数据审核、分类与封面、歌手与歌单、AI 和计算节点配置、TV 聚会、音轨处理、WebDAV 共享、歌曲缓存以及播放历史和实例。Go 在接收请求前执行 dbmate migrate；超时或失败即停止启动，普通启动不执行 down。首次运行时管理员由认证初始化逻辑创建，默认账号见[认证说明](authentication.md)。

空库缓存策略为 `HYBRID`，`revision=0`，高/低水位为 90%/75%；播放统计为 `ready=1/phase=READY`，游标从零开始。媒体来源、歌曲、缓存条目、播放实例和历史均为空，不需要历史回填。默认分类标签和关闭的功能配置由初始化脚本提供，用户凭据与曲库不写入种子数据。

旧版迁移文件已合并，不提供已有开发数据库的升级路径。此后新增结构变更应增加新的版本化迁移，已发布的初始化脚本不作为业务更新入口。初始化迁移的 down 会删除全部业务表，只用于明确需要重建的隔离空库检查。

后续版本包括两种方言的 `202611080001_background_tasks.sql` 与 `202611090001_background_task_batches.sql`；数据库通过 dbmate 按版本顺序应用全部后续迁移。任务中心记录清理及该增量迁移的 down 不删除原业务数据，见[后台任务中心](../contracts/background-tasks.md)。

## 配置切换

默认 `SINGVERSE_DB_DRIVER=sqlite`。`SQLITE_PATH` 默认 `./data/singverse.db`，相对启动进程工作目录解析为绝对路径，连接前创建父目录；只接受普通文件路径，不接受连接 URL 或内存库。SQLite 使用 CGO-free `modernc.org/sqlite` 与单连接 `database/sql` 池，sqlx 扩展复用同一连接池。

`SINGVERSE_DB_DRIVER=mysql` 显式选择 MySQL，使用 `go-sql-driver/mysql`；`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD` 作为独立参数，密码无默认值。数据库须预先存在；启动真实连接失败即终止，不静默回退。Go 原生进程不自动加载根 `.env`。

驱动选择互斥，切换不搬数据。Go 全新部署不复用旧 Java 数据库和账号；旧卷独立保留。`/api/health` 检查数据库，仅公开整体 UP/DOWN，不泄露凭据和表结构。SQLite 用于单业务进程；启动持有部署锁，单连接本身不代替进程互斥。

## 真实检查

从仓库根运行 `make db-fresh-check`，使用固定版本的真实 dbmate 和临时 SQLite 文件，验证从空库初始化、重复 migrate 不变、增量 migrate/down 保留曲库数据、逐级回退后业务表全部移除，以及再次 migrate 恢复相同结构与种子数据。检查核对两种方言的基线与增量版本集合，基线 up 不含历史改表或数据转换语句，并验证缓存默认值、统计就绪、外键及关键 CHECK 约束。它不连接或重置开发数据库，也不代替真实 MySQL 验证。

`make db-sqlite-check` 执行迁移检查及真实 Go SQLite 事务/锁/HTTP 测试；`make go-backend-test` 覆盖全部 SQLite 业务行为。

`make db-mysql-check` / `make go-mysql-check` 自动创建随机命名、仅回环发布的临时 MySQL 容器，运行真实 Go 业务 HTTP、类型、事务和二进制列验证，只删除自己创建的测试容器。无需指向已有业务库。`make db-mysql-up` 仍可独立启动开发 MySQL；开发运行时显式导出配置，不把其数据卷当测试清理目标。无数据库或启动失败时返回非零。

连接成功仅说明驱动、凭据和基础 SQL 可用。修改业务时仍须分别验证两种方言的类型、时间、事务、索引、自增键、锁及 JSON 行为；隔离测试通过不代表 NAS 实机部署已验收，实际结果见[验证记录](verification.md)。

## 显式 SQL 与事务约定

业务查询与写入 SQL 显式放在 `services/singverse-go-backend/internal/<domain>/sql/*.sql`，以 `go:embed` 加载。参数使用 `?` 或平台 `@{name}` 绑定，禁止外部输入构造 SQL 标识符、条件或排序。固定查询可按受控分支组合，禁止 ORM 隐藏业务语句。健康固定 SQL、迁移 DDL 与隔离测试数据准备单独管理。

应用服务使用 `database/sql + sqlx`；连接为 `*sqlx.DB`，通过 `BeginTxx` 创建 `*sqlx.Tx`，以 `store.DBTX` 共享事务。固定结果使用独立行模型和显式 `db` 标签，通过带 context 的 `Get` / `Select` / `StructScan` 读取；通用投影由平台使用 `MapScan` 并保留文本、二进制和 SQL NULL 的驱动语义。`@{name}` 只作为嵌入 SQL 的标记，平台交给 sqlx 完成命名值绑定。跨模块调用在调用方事务内复用执行器，SQLite 单连接事务内不得从服务数据库另取连接。来源/规则绑定、索引发布、人工字段保护和后台任务投影在同一事务校验修订并提交；目录遍历和外部 HTTP 在事务外执行。

配置 JSON 的运行时兼容与数据库初始化分开处理。例如 `filename_parser_config` 的旧数组读取只投影为共享规则库与方案，首次成功修改才在 revision 校验下写入 schemaVersion=2；这是配置文档行为，不依赖已删除的数据库迁移。来源绑定变更与方案删除继续使用事务协调，扫描在目录遍历完成后用短事务核对来源及规则修订，再替换正式索引。

任务中断恢复、缓存文件校验、删除日志重试及统计重建仍由各业务运行时逻辑处理。统计未就绪时暂停普通缓存淘汰，播放继续；空库初始已就绪不取消这些保护。具体语义见[媒体库管理](media-library.md)、[转码与缓存](transcoding.md)和[歌曲缓存契约](../contracts/song-cache.md)。

## 备份与保护

数据库文件、WAL/SHM、凭据和构建产物不得提交。业务数据库包含分类与 TV 首页图片、任务状态和加密配置；文件缓存、封面目录、音轨衍生文件等独立数据目录按各模块说明备份。加密配置必须保留匹配的认证主密钥，详见[认证说明](authentication.md)。

先停止写入或使用数据库一致性备份工具，不直接复制正在写入的 SQLite 文件作为完整备份。Compose 命名卷默认保留，修改初始化环境变量不会重置已有卷；停止服务不使用 `down -v`。媒体原件继续只读，不与缓存、衍生文件或数据库初始化混用。
