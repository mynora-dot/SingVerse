# 数据库初始化与验证

当前按**首次部署、从零建库**维护 SQL。SQLite 与 MySQL 各保留一份完整的 dbmate 初始化基线：

- `migrations/sqlite/202611070001_initial_schema.sql`
- `migrations/mysql/202611070001_initial_schema.sql`

此前每种数据库的 38 份迁移已合并并删除。基线直接定义最终表、字段、约束、索引和必要单例，不执行历史补列、歌曲表重建或旧数据回填。它适用于空数据库，不提供旧版本数据库升级路径；应用仍通过 dbmate 初始化并在 `schema_migrations` 记录唯一版本 `202611070001`。后续在此基线上新增迁移，不修改已部署的基线。首次增加后续迁移时，需同步扩展从零检查的版本集合及逐级回退断言；本轮检查明确要求只有当前初始化版本。

## 结构与初始值

业务表覆盖认证、媒体来源与解析、歌曲索引和扫描、元数据审核与分享、AI 与分类、歌手与图片、TV 与聚会、歌单、转码与音轨、缓存、真实播放统计及缺歌反馈。SQLite/MySQL 保留各自适用的文本、二进制、自增及索引定义；Java 不自动建表，业务 SQL 继续放在 MyBatis Mapper XML。

- WebDAV 的文件大小和修改时间允许为空；扫描暂存与正式索引采用相同语义。
- 缓存策略直接默认 `HYBRID`，设置 revision 从 `0` 开始，高/低水位为 `90/75`。
- 歌曲、播放记录、缓存索引、固定偏好及播放实例初始为空；播放统计单例直接为 `ready=1, phase=READY`，无需等待历史回填。
- 管理员仍由应用认证初始化流程创建及计算密码摘要，SQL 不预置密码、密钥、真实设备或媒体数据。
- 外键、唯一键、CHECK 与业务查询索引在首次建库时创建，回退按依赖逆序删除业务表。`down` 仅用于可丢弃的验证库，不由应用启动流程自动执行。

## 从零验证

```sh
# 无需启动后端、前端或 Docker；仅使用临时 SQLite，结束自动清理。
make db-fresh-check

# 从临时 SQLite 启动真实 Java 服务并验证 HTTP、Mapper 和业务结构。
make db-sqlite-check

# 完整 Java 业务回归；各测试使用各自隔离数据。
make java-backend-test
```

`db-fresh-check` 使用真实 dbmate，验证首次 `up`、重复 `migrate`、`down` 和再次建库，比较完整结构及种子，并检查外键、热度/固定约束、空业务数据和默认值。它固定使用自身临时目录，不读取本地 `.env`、不使用 `SQLITE_PATH`、不操作任何已有数据库。此检查也接入 `db-sqlite-check` 和 Java CI。

MySQL 必须使用**另外创建的空数据库**，通过 `MYSQL_HOST/MYSQL_PORT/MYSQL_DATABASE/MYSQL_USER/MYSQL_PASSWORD` 指向隔离实例，再运行 `make db-mysql-check`。该命令会执行业务写入测试，不能指向实际使用的数据库。连接成功不等于业务兼容，未运行真实 MySQL 检查时应明确标记未验证。

手工从零启动可给 `SQLITE_PATH` 指定一个全新文件路径，或使用新的 Docker Compose 项目与其独立数据卷；不要用旧库验证这份基线。现有命名卷和实际数据库由使用者保留或另行处理，本次整理不删除它们。

连接及 Profile 见[数据库策略](../docs/database-strategy.md)，账号与主密钥见[认证说明](../docs/authentication.md)。数据库、WAL/SHM、凭据和主密钥不得提交；切换 Profile 不自动迁移数据。
