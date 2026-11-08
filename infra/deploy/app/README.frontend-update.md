# Linux AMD64 业务前端离线更新

本包只包含 `singverse-frontend:offline-amd64` 运行镜像，不含 Java、compute、数据库或 Compose。仅用于前后端 API 契约仍兼容的前端更新；涉及接口、数据库或计算契约变化时，使用配套完整版本部署。

随包附带 [验收与运维指南](README.operations.md)。其中维护命令在原业务部署目录执行，继续使用原项目名和配置，不在更新包目录另建业务项目。

1. 校验并导入：

```sh
sha256sum -c SHA256SUMS
docker load -i images.tar
```

2. 进入**原业务部署目录**，保留原项目名、Compose 与卷。确认原前端服务使用 `singverse-frontend:offline-amd64` 标签；若不同，按本次交付标签修改原 Compose。先记录旧镜像 ID，必要时打保留标签用于回退。
3. 使用原 `-f` 文件组合和项目名重建前端，例如原部署仅使用一个 `docker-compose.yaml` 时：

```sh
docker compose -f docker-compose.yaml up -d --no-deps --no-build --pull never --force-recreate singverse-frontend
docker compose -f docker-compose.yaml ps
```

4. 用实际 NAS 业务 URL 打开管理页并强制刷新，检查登录、静态资源及更新涉及的页面。只执行 restart 不会替换容器镜像。

无需替换 Java 或计算节点，也不更换数据库、密钥及模型卷。维护者先从同一版本源码构建上述 AMD64 前端镜像，再在仓库根执行：

```sh
python3 scripts/package-app-offline.py --frontend-only --output /tmp/SingVerse-Frontend-Update.zip
```
