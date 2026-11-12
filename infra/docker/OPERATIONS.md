# Docker 运维

固定使用初次部署时的项目名、配置文件组合、绑定地址和数据目录。默认业务项目为 `singverse-go-app`；独立计算节点为 `singverse-compute-node`。所有操作保留 `--project-directory .`，默认 `.env` 从项目根读取。

## 持久数据

业务卷仍为 `singverse-data`、`singverse-secrets`、`singverse-downloads`、`singverse-media`；同机计算仍为 `singverse-compute-auth`、`singverse-transcode-cache`、`singverse-audio-models`。实际 Docker 卷名带项目名前缀。计算 cache 卷也包含硬件、模型选择及资格记录，不是全部可丢弃缓存。媒体原件默认只读。

停止使用 `down`，不使用 `down -v`。不清理其他项目，不自动重置账号、密钥或数据库。

## 停机备份与恢复

1. 用本次完整 Compose 组合执行 `stop`，等待服务退出后再复制 SQLite 文件。
2. 通过 Docker/NAS 的卷备份功能，备份业务 data 与 secrets、计算认证与设置卷、下载卷；外部媒体和 WebDAV 凭据按各自的存储备份策略保护。数据库及主密钥必须配套保存。
3. 记录镜像版本或 digest、根 VERSION、项目名及使用的配置组合；`.env` 单独安全保存，不提交 Git。
4. 先在另一个隔离项目和新卷中恢复，确认管理员登录、媒体访问及计算连接后，再安排正式切换。恢复不得覆盖正在运行的数据库。

## 升级

先完成停机备份。源码路线更新完整同版本源码后执行原组合的 `build`、`up -d --build --wait`；镜像路线固定新版本后执行原组合的 `pull`、`up -d --no-build --wait`。确认 health、登录、H5、播放和计算功能，再结束维护。

失败时保留日志、卷和备份。旧镜像不一定能读取新迁移后的数据库；回退需要恢复匹配的数据及密钥备份，不直接把新数据库交给旧镜像。

## 从旧三容器部署切换

业务项目名和卷身份保留，但 HTTP 入口从旧 Nginx 改为业务容器的 8080 端口映射。先使用旧配置停止旧业务及 Nginx 容器以释放宿主端口，再用新配置启动；只移除确认属于该项目的旧 Nginx 容器，保留数据卷。新配置不会自动停止其他项目或删除 orphan 容器。

旧 Java 部署不能复用 Go 数据库或账号；新交付不再提供 Java 回退 Compose。历史命名卷保持原状，由管理员单独管理。

## 检查

`make docker-check` 只读检查当前部署。`make go-container-check`、`make auth-docker-check` 和 `infra/docker/check-compose.py` 及 `infra/docker/check-compute-compose.py` 使用随机隔离资源，并只清理自身资源。真实微信、电视、NAS 和其他 CPU/GPU 架构验收单独记录。
