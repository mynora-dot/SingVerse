# Docker入口

[compose.app.yaml](../../compose.app.yaml)启动Nginx前端与Java后端，默认使用独立SQLite命名卷。从仓库根执行 `make docker-up`，访问 http://localhost:18080/ 。构建、检查、日志、停止及局域网访问见[Docker说明](../../docs/docker.md)。

## 开发MySQL

根[compose.yaml](../../compose.yaml)独立提供MySQL8.4、命名卷、应用账号SQL健康检查和仅回环端口。应用Docker命令不操作该项目。Python骨架继续使用 `make compute-run`，暂未提供计算节点镜像或GPU映射。

首次配置时按.env.example建立本地.env并修改密码占位值，已有.env不要覆盖。从仓库根执行：

```sh
make db-mysql-up
set -a
. ./.env
set +a
make db-mysql-check
docker compose stop mysql
```

检查从宿主连接，MYSQL_HOST使用127.0.0.1；容器内部服务名仅用于同网络进程。停止保留命名卷，不擅自执行down -v。修改初始化密码不会更新已初始化卷的账号；切换数据库不迁移数据。NAS、GPU和跨架构验收仍需实际设备。
