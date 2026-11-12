# Java → Go 切换边界

Go 是唯一生产业务主服务，Java 源码、Maven 工作流及 Docker 回退入口已退休。历史实现与验收证据见[历史验证](go-migration-verification.md)，不作为当前部署命令。

## 数据边界

新 Go 部署不自动迁移旧 Java 数据库或账号，也不把 Go 写入后的数据库交给 Java。原 `singverse-app_*` 卷及旧本地配置保持原状。当前业务项目名为 `singverse-go-app`，不得改为旧 Java 项目名复用旧库。

## 当前部署

仅提供[源码构建和预构建镜像](../infra/docker/README.md)两条路线，Go 内置前端，compute 按需部署。原独立 Nginx 切换步骤、备份及匹配版本回退见[运维](../infra/docker/OPERATIONS.md)。

Go/Python HTTP 契约及业务数据库职责继续保留；生产默认链路不调用 JVM，不提供旧源码或旧迁移链的隐式兼容。
