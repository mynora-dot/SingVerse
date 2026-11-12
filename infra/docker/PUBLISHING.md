# 镜像发布

统一工作流 `.github/workflows/singverse-images.yml` 在普通提交、PR 和手动运行时只构建检查；发布非 prerelease 的正式 GitHub Release 时才推送。Release 标签必须是 `v` 加根 VERSION 去掉 `-SNAPSHOT` 的值，例如 `0.1.0-SNAPSHOT` 对应 `v0.1.0`。

## Actions 配置

在仓库 Settings → Secrets and variables → Actions 配置 Variables：

| 变量 | 配置 |
|---|---|
| GHCR_NAMESPACE | 可选，默认当前仓库所有者；使用小写命名空间 |
| DOCKERHUB_NAMESPACE | 必填，Docker Hub 命名空间，例如 mynora |
| ACR_REGISTRY | 必填，阿里云实际 Registry 域名，不带协议或路径 |
| ACR_NAMESPACE | 必填，阿里云命名空间，例如 mynora |

配置 Secrets：`DOCKERHUB_USERNAME`、`DOCKERHUB_TOKEN`、`ACR_USERNAME`、`ACR_PASSWORD`。GHCR 使用工作流 `GITHUB_TOKEN`，发布 job 授予 packages:write。账号必须对所配置的命名空间有推送权限；提前创建各平台要求预建的 `singverse` 和 `singverse-compute` 仓库并设置所需拉取可见性。缺少任一配置时发布前失败，不跳过平台。

## 构建与标签

业务镜像名 `singverse`，计算镜像名 `singverse-compute`。业务版本标签如 `0.1.0`，计算标签如 `0.1.0-base`、`0.1.0-cuda`。架构标签追加 `-amd64` 或 `-arm64`。硬件矩阵见 GPU 指南。

每个运行库、架构只构建一次，同时产生本地测试镜像及 OCI 制品；真实镜像检查通过后，逐个验证 OCI blob 的校验值，并确认 OCI config digest 与已验收运行镜像一致，再上传临时 Actions Artifact。发布 job 使用同一 OCI 制品向三个平台复制，保留 digest，再生成确定性的多架构 OCI index。公共发行不提供离线镜像 ZIP 或源码部署 ZIP；Actions Artifact 只是发布过程的临时内部制品。

版本标签不可替换为不同内容。同 digest 视为已完成，可重试发布 job；认证、网络错误不视为标签不存在。某个平台失败时整个工作流失败；已完成的平台保留对应版本，重试补齐，不尝试删除远端内容。

只有全部版本、架构和平台确认成功，才开始更新业务 `latest` 和计算 `latest-<运行库>`。跨 Registry 不存在原子事务，latest 更新中途故障时仍会报错，需要重试完成；固定版本和 digest 是可靠升级依据。

本次代码修改不登录 Registry 或执行真实推送。配置工作流不代表 GitHub Actions 已远程通过，也不代表已有可拉取版本。
