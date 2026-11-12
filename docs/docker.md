# Docker 部署

当前仅提供[源码构建和预构建镜像](../infra/docker/README.md)两条路线，业务由 Go 内置完整前端，compute 按需部署。

- [GPU 与独立计算节点](../infra/docker/GPU.md)
- [备份、升级和旧部署切换](../infra/docker/OPERATIONS.md)
- [GHCR、Docker Hub、阿里云发布配置](../infra/docker/PUBLISHING.md)

旧源码部署包、离线镜像 ZIP、独立 Nginx 及 Java 回退配置已移除。历史验收记录只说明对应旧版本，不能作为新交付验证结果。
