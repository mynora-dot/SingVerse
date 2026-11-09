# 部署后的验收、备份与更新

适用于完整仓库、独立业务包及独立计算节点。首次安装按仓库 `infra/docker/README.md` 或包内 `部署说明.md` 完成；本页的命令在 **Linux NAS/主机的 Bash** 中执行。所有维护操作沿用首次部署的项目名、文件组合和私有配置，媒体原件另行备份。

## 固定部署入口

先进入仓库根，按实际部署定义一个命令。以下默认基础栈；若首次用了 `-p`，这里也使用原项目名：

```bash
SV_PROJECT=singverse-app
sv() {
  docker compose --project-directory . -p "$SV_PROJECT" \
    -f infra/docker/compose/compose.app.yaml "$@"
}
sv config --quiet
sv ps -a
```

例如，NVIDIA AI 加本地曲库使用下面的完整函数替换上面的 `sv`；必须已经配置实际 `SINGVERSE_MEDIA_PATH` 和 GPU 驱动：

```bash
sv() {
  docker compose --project-directory . -p "$SV_PROJECT" \
    -f infra/docker/compose/compose.app.yaml \
    -f infra/docker/compose/compose.media.yaml \
    -f infra/docker/compose/compose.audio-cuda.yaml "$@"
}
```

其他硬件使用首次选择的覆盖文件；gfx1103 保持 ROCm 基础文件在 native 文件之前。HTTPS 覆盖文件也要保留。独立通用节点用 `compose.compute.yaml` 作基础，项目名默认 `singverse-compute-node`。

源码/离线上传包用户进入完整解压目录，使用以下定义，项目名分别为 `singverse-business` 或 `singverse-compute-node`：

```bash
SV_PROJECT=singverse-business
sv() {
  docker compose --project-directory . -p "$SV_PROJECT" -f docker-compose.yaml "$@"
}
```

函数仅在当前终端生效；重新登录后需重新定义。不要在自定义栈上改用只加载基础配置的 `make docker-up`。部署记录至少保留项目名、源码版本/包摘要、文件顺序、实际绑定地址和端口、镜像 ID；私有 `.env` 与修改后的 YAML 作为私有备份保存，不上传日志或公开工单。

## 首次验收与升级后复验

| 检查 | 通过依据 |
|---|---|
| 服务状态 | `sv ps -a` 中预期服务 healthy，无反复重启；独立业务包应为两个服务 |
| HTTP 与设备访问 | 部署机访问 `/api/health` 返回 UP；另一台局域网设备能打开管理页和 `/party/`；地址使用 NAS 实际 IP |
| 账号和原件 | 修改初始密码，按需配置 TOTP；Java 的 `/media` 为只读，曲库添加的是容器路径或 WebDAV 地址 |
| 性能节点 | 管理页连接测试通过，Java→compute 与 compute→业务入口两个方向都可达 |
| AI | 下载/导入模型后完成所选设备试跑，再用完整真实歌曲分离；核对实际 backend、模型版本、回退原因与输出时长，试听人声残留和乐器损伤 |
| 视频 | 使用实际素材播放，核对实际解码、编码、回退和首片时间；分别验证 SDR/HDR，不以 AI 试跑代替 |
| 重建后的持久化 | 按同一配置重新 `up -d` 后，账号、曲库、节点身份和模型仍保留；环境变化后重新试跑 |

同栈默认服务为 Java、Nginx 和 compute。节点 `/health`、设备枚举和模型文件存在均不代表最后两项通过。保留每次测试的素材时长、设备、版本、耗时及错误；合成音只验证链路，不能用于真人声质量结论。

## 哪些数据要保留

| 容器内路径 / 默认逻辑卷 | 内容 |
|---|---|
| Java `/data` / `singverse-data` | SQLite、曲库/账号设置、封面、持久缓存、兼容版本和已接收的伴奏 |
| Java `/secrets` / `singverse-secrets` | 解密业务凭据的主密钥，必须与原数据库配套 |
| Java `/downloads` / `singverse-downloads` | 洛雪下载音频、歌词/封面和未完成临时文件；NAS 绑定时备份实际宿主目录 |
| compute `/run/singverse-compute` / `singverse-compute-auth` | 连接令牌、配对身份及 owner 信息 |
| compute `/var/lib/singverse-models` / `singverse-audio-models` | 模型权重与模型配置文件 |
| compute `/var/cache/singverse-transcode` / `singverse-transcode-cache` | 临时产物、任务记录、`hardware-settings.json`，以及 `audio-roles/models/settings.json` 中的模型选择/试跑资格 |

最后一卷同时存储设置，不能把整个卷当作可随意清空的缓存。更换设备或驱动后，即使恢复旧资格记录，也需重新检测和试跑。`/media` 原件及外部 WebDAV 不包含在下述卷备份中。

## 停机备份示例

可优先使用 NAS 的一致性快照。手工备份时，先在管理端停止提交新任务，等待或取消在途任务，然后使用前面定义的 `sv`。以下只读挂载源卷，使用现有镜像中的 tar，不额外下载工具镜像。

```bash
set -eu
umask 077
# 替换为已有权限、空间充足的绝对目录；不要放在曲库或被备份卷内。
SV_BACKUP_DIR="/srv/backups/singverse-$(date +%Y%m%d-%H%M%S)"
test ! -e "$SV_BACKUP_DIR"
mkdir -p "$SV_BACKUP_DIR"
SV_CONTAINER_IDS="$(sv ps -a -q)"
test -n "$SV_CONTAINER_IDS"
SV_HELPER_CONTAINER="$(sv ps -a -q | head -n 1)"
SV_BACKUP_IMAGE="$(docker inspect --format '{{.Image}}' "$SV_HELPER_CONTAINER")"

# 只收集命名卷，排除媒体原件；共享认证卷去重。
SV_VOLUMES="$(docker inspect --format \
  '{{range .Mounts}}{{if and (eq .Type "volume") (ne .Destination "/media")}}{{.Name}}{{println}}{{end}}{{end}}' \
  $SV_CONTAINER_IDS | sort -u)"
test -n "$SV_VOLUMES"
docker run --rm --pull never --network none --read-only --user 0:0 \
  --entrypoint /bin/sh "$SV_BACKUP_IMAGE" -ec 'command -v tar >/dev/null'
sv stop
for SV_VOLUME in $SV_VOLUMES; do
  docker volume inspect "$SV_VOLUME" >/dev/null
  test ! -e "$SV_BACKUP_DIR/$SV_VOLUME.tar"
  docker run --rm --pull never --network none --read-only --user 0:0 \
    --mount "type=volume,src=$SV_VOLUME,dst=/volume,readonly" \
    --entrypoint tar "$SV_BACKUP_IMAGE" -C /volume -cpf - . \
    > "$SV_BACKUP_DIR/$SV_VOLUME.tar"
done
(
  cd "$SV_BACKUP_DIR"
  sha256sum -- *.tar > SHA256SUMS
  sha256sum -c SHA256SUMS
)
sv start
sv ps -a
```

备份目录可能含完整数据库、主密钥和节点令牌，按私有数据保存；另外记录同版本镜像与完整配置。备份文件已经存在或某一步失败时停止排查，不覆盖旧备份，不把残留 `.tar` 当作成功结果。`set -e` 失败后服务可能仍停止，确认原因后再执行 `sv start`。纯 bind 挂载的数据不会进入这个循环；自定义 `/data`、`/secrets` 或模型 bind 时，需在同一次停机中另行快照相应宿主目录。

## 在新项目验证恢复

先取得备份对应版本和架构的镜像，保留原部署。选择从未使用的项目名，给验证栈设置空闲的回环端口，继续使用原文件组合；上传包端口是 YAML 中的字面值，需编辑验证用副本。先 `create`，不启动业务写入：

```bash
SV_PROJECT=singverse-restore-check
test -z "$(sv ps -a -q)"
sv config --quiet
sv create --no-build --pull never
(
  cd "$SV_BACKUP_DIR"
  sha256sum -c SHA256SUMS
)
```

以下只演示恢复业务数据卷，实际还必须把配套密钥、认证、模型和设置恢复到各自对应的目标卷。根据 `docker volume ls` / `docker inspect` 确认真实卷名；不要指向仍在使用的原业务卷。跨主机时，先将 `SV_BACKUP_IMAGE` 设为新主机上已加载、包含 sh/tar 的同版本镜像 ID：

```bash
SV_TARGET_VOLUME=singverse-restore-check_singverse-data
SV_SOURCE_ARCHIVE="$SV_BACKUP_DIR/singverse-app_singverse-data.tar"
docker volume inspect "$SV_TARGET_VOLUME" >/dev/null
docker run --rm -i --pull never --network none --read-only --user 0:0 \
  --mount "type=volume,src=$SV_TARGET_VOLUME,dst=/volume" \
  --entrypoint /bin/sh "$SV_BACKUP_IMAGE" -ec '
    test -z "$(ls -A /volume)" || { echo "目标卷非空，停止恢复" >&2; exit 1; }
    tar -C /volume -xpf -
  ' < "$SV_SOURCE_ARCHIVE"
```

用同样方式恢复其他配套卷，保留 UID/GID 与权限；遇到非空卷不要删除内容来绕过检查。全部恢复完成后 `sv start`，按验收表检查登录、MFA、凭据解密、曲库、节点连接、原唱/伴奏播放，再考虑切换业务入口。验证期间保持旧业务与验证栈的计算任务隔离。独立节点仍有原 owner 约束，迁移应恢复原身份，不清空配对卷强行重配。

## 更新与回退

1. 记录现有版本和镜像 ID，完成上面的配套备份。发布镜像与数据库兼容性以实际版本说明为准；当前空库基线不提供旧迁移链升级。
2. 在同一版本源码上先 `sv config --quiet`、`sv build`。构建不必停止原服务；切换前停止新任务并等待或取消在途任务。不要用自己的未提交开发目录当作已发布版本。
3. 源码部署执行 `sv up -d --no-build --wait --wait-timeout 180`；离线镜像先 `docker load -i images.tar`，再加 `--pull never`。同标签交付可加 `--force-recreate`；`restart` 不会应用新镜像和环境变量。
4. 共同更新 Java、完整前端与 compute，再按验收表复验。GPU/运行库变化时重做资格测试；保留认证、权重及设置卷。
5. 回退前确认数据库兼容。若不兼容，停止写入，使用原版本及升级前成套备份恢复；不能仅换回镜像或只恢复数据库。

`sv down` 用于停止并移除容器/网络，保留命名卷；不加 `-v`。空间紧张时先区分模型、设置、持久业务缓存和媒体原件，再使用管理页支持的清理操作。

## 分离慢与构建失败的定位

- AI 总耗时包含回源、解码、依赖/模型加载、GPU 初始化、推理、编码、传输与业务轮询。记录实际 backend/fallbackReason，再结合 `docker stats --no-stream` 和 GPU 工具定位；短曲时 GPU 只短暂繁忙并不等于未启用加速。当前逐曲进程启动方式仍有固定开销，不能把模型复用诊断的耗时当作已发布性能承诺。
- `unhealthy` 先读取 `sv logs --tail 100`，检查磁盘、内存、权限、数据库版本与构建输入。Mac 上传源码时保留完整仓库和符号链接；使用项目生成的 ZIP 可统一权限。Docker 白名单和打包器会过滤 `.DS_Store`、`._*` 与 `__MACOSX` 元数据；它们不是源码，混入 XML 会导致解析失败。
- 分享排障材料时保留版本、实际后端、耗时、状态码和无凭据的错误信息，移除令牌、Cookie、私有 `.env`、带签名参数的媒体 URL 和完整数据库。
