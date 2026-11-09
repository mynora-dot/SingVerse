# Docker 测试与交付

从零部署的维护入口已统一到 **[Docker 部署指南](../infra/docker/README.md)**。该文档随仓库交付，包含完整命令、Compose 版本要求、默认三容器栈、WebDAV/本地只读媒体、独立节点双向回源、离线业务包、备份和升级。

部署完成后的验收表、固定 Compose 命令、可执行卷备份、空卷恢复与更新/回退见 **[验收与运维指南](../infra/docker/OPERATIONS.md)**。源码与离线部署包均附带该指南。

GPU 视频和 AI 伴奏分别部署与验收，详见 **[GPU 与 AI 部署](../infra/docker/GPU.md)**；AMD gfx1103 使用 `compose.audio-rocm.yaml` 后再叠加 `compose.audio-rocm-native.yaml`。容器不能修复宿主直通、驱动或固件问题，也不会自动更新这些配置。

默认应用为 Nginx + Go + base compute，数据库使用 Go 数据卷中的 SQLite。默认 compute 不含 AI 运行库或模型；不能将健康通过视为 GPU 或伴奏分离通过。Go 负责业务库和授权，compute 经任务授权回源，媒体原件仅给 Go 只读访问。

仓库自检入口：

```sh
make docker-config-check  # 无需 daemon，解析配置组合、构建输入和保护规则
make docker-up            # 默认基础组合；自定义部署保持原 -f 文件组合
make docker-check         # 已启动的默认应用 HTTP/认证/基础媒体验证
make docker-down          # 保留所有命名卷，不加 -v
```

数据库首次部署基线见 [数据库 README](../database/README.md)，模型与运行库参数见 [compute README](../services/singverse-compute/README.md)。本次 Go 实测见 [Go 迁移验证](go-migration-verification.md)；[工程验证记录](verification.md) 与 [2026-11-08 NAS ROCm 记录](verification-rocm-2026-11-08.md) 保留各自的历史实现和硬件范围，不能用作本次 Go 真机通过的证据。
