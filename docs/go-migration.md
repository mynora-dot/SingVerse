# Java → Go 业务主服务替换

全量功能替换和分阶段验收已完成，采用全新部署。生产默认服务为 `services/singverse-go-backend`，保留 Vue/H5、Flutter、TV 和 Python 的现有 HTTP 协议。Go 管理授权、曲库、任务与唯一业务数据库写入；Python 继续承担媒体探测、转解码及 AI 重计算。

## 数据与切换边界

不迁移已有 Java 数据库、账号、会话或加密配置。Go 从空 SQLite/MySQL 初始化，重新设置管理员与来源；原有媒体继续只读。旧 `singverse-app_*` 命名卷、配置和镜像保持原样，不能将 Go 写入后的数据库挂给 Java，也不承诺两种实现共享写入后的库。

默认 `compose.app.yaml` 与兼容入口 `compose.go.yaml` 都使用 `singverse-go-app` 项目；命名卷因此与旧 `singverse-app` 隔离。独立业务部署包使用 `singverse-go-business`。不要通过 `-p singverse-app` 覆盖新默认项目名，不执行 `down -v`。

上线前保存旧部署 YAML、镜像 ID、入口和匹配的数据库/主密钥备份。停掉旧入口占用后，在新项目、新卷和空闲端口启动 Go，验证登录、全部静态资源、客户端授权、来源扫描、真实媒体 Range、Python 双向回源及任务恢复，再切客户端入口。新旧服务不同时成为同一数据集的写入者。

## 回退

Java 受控源码、Maven 检查目标与工作流已在最终验收后退休，未跟踪的本地数据和配置保持原样。回退使用已保存镜像，`infra/docker/compose/compose.java-rollback.yaml` 不依赖源码构建，使用旧 `singverse-app` 项目和其原卷。默认旧镜像标签为 `singverse-java-backend:java-rollback`、`singverse-frontend:java-rollback`，可通过 `SINGVERSE_JAVA_ROLLBACK_IMAGE`、`SINGVERSE_JAVA_ROLLBACK_FRONTEND_IMAGE` 指定已核对版本。旧媒体、下载、HTTPS、macOS 覆盖文件分别保存为 `compose.java-rollback.*.yaml`。

停止 Go 使用 `make docker-down`，保留 Go 卷；确认原入口端口空闲后执行 `make java-rollback-up`，或使用原部署保存的完整 Compose 组合。Java 读取自身旧数据，Go 上线后新建的设置、歌曲、播放与下载不自动回流。回退不删除或重置任何一边的数据。

## 验收与实现约束

- 版本来自根 `VERSION`，静态产物来自完整前端 `dist`，业务二进制与容器不依赖 JVM/Node。
- Gin 负责 HTTP，应用服务显式注入依赖，SQL 放在模块内嵌文件，通过 `database/sql + sqlx` 与事务执行器读取和写入；无 ORM、Redis 或外部消息队列。
- 公共 API、状态码、分页、UTC 时间、Problem、Cookie/CSRF/MFA 和四端/Python 媒体授权必须按真实协议测试。
- SQLite/MySQL 事务、后台任务恢复、取消、共享包收据、文件发布与缓存完整性分别验证。
- 实际结果和剩余硬件/设备边界记录在 [Go 验证](go-migration-verification.md)，定时任务对应表见 [后台迁移映射](../contracts/go-scheduled-migration.md)。

数据库驱动切换与语言替换相互独立，切换 `SINGVERSE_DB_DRIVER` 不搬迁数据。历史 Java、NAS、微信或 TV 报告保持其原始日期与范围，不能作为本次 Go 真机通过的证据。
