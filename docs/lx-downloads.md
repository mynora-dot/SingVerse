# 洛雪歌曲下载

管理员进入「添加与整理 → 下载歌曲」（`#/library/downloads`）。先选择保存位置，再导入并启用自己提供的 LX 2.0 GoScript 音源，即可搜索歌曲，或读取公开歌单、专辑后选择下载。当前目录适配包含网易云、QQ、酷狗、酷我、咪咕；实际可选平台和音质取决于音源声明。应用不内置音源、不分发歌曲，也不提供平台账号登录。

## 保存位置

在本地媒体库添加或编辑表单的「歌曲文件夹」下勾选「用于保存洛雪下载」，或在歌曲下载页选择现有本地媒体库。全局只绑定一个位置，卡片显示「下载保存位置」。默认关闭，WebDAV 不能作为下载位置；编辑请求省略 `useForLxDownloads` 时保留原绑定。

保存绑定和创建下载任务时，Go 会实际创建并删除一个临时文件以验证权限。目录必须是服务器可见的绝对真实路径，不能使用符号链接。更换时页面显示原位置与新位置；原位置有未完成批次时，不能清除绑定、修改路径或移除媒体库。先完成或取消任务，再修改配置。切换位置只影响未来任务，原文件和记录保留。

Docker 默认提供独立持久卷 `singverse-downloads:/downloads`，添加媒体库时选择 `/downloads`。原有 `/media` 仍按只读挂载。要将下载保存在 NAS 文件夹，可提前创建目录、授予容器 UID/GID `10001:10001` 写入与目录遍历权限，再使用覆盖文件：

```sh
SINGVERSE_DOWNLOAD_PATH=/absolute/path/to/downloaded-songs \
docker compose --project-directory . --env-file .env \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.downloads.yaml config --quiet

SINGVERSE_DOWNLOAD_PATH=/absolute/path/to/downloaded-songs \
docker compose --project-directory . --env-file .env \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.downloads.yaml up -d --wait
```

路径需替换为实际已经存在的目录；覆盖文件不自动创建宿主目录。NAS ACL、NFS 的 UID 映射及 SMB 挂载参数由部署者配置，避免为了下载开放整个原始曲库写权限。后续启动、更新与维护保持同一组覆盖文件，并将下载卷或 NAS 下载目录纳入备份。

## 音源和找歌

「管理音源」支持 UTF-8 `.js` 文件（最大 9 MB）或公网 HTTPS 链接。可以保存多份，启用一份；高级兼容模式默认为 `mobile`，脚本要求桌面环境时选择 `desktop`。导入和启用均初始化脚本，失败时保留已有配置；测试只证明初始化和能力声明成功，不能证明每首歌可用。正文、导入地址连同其中的查询密钥加密保存，列表不回显，备份需同时保留数据库与匹配的认证主密钥。

搜索保留不同平台、不同版本，相同平台的真实歌曲标识不会由客户端拼造。某个平台失败时，其余结果仍可使用。歌单、专辑按页读取，每页通常 20 首，最多读取 500 页；平台只公开部分歌曲、提前停止、缺少必要标识或达到上限时会显示原因和数量。勾选「选择已读取歌曲」仅选择当前已读且尚未下载的曲目。受限目录需在原平台检查公开权限。

确认下载时显示数量、保存位置、音源与音质。默认「自动」按音源声明从高到低解析；明确的音质不可用错误会尝试下一档，网络、脚本失效等错误直接提示。手动音质不静默降级。提交后保留搜索与预览；活动数量显示在下载记录页签。下载在后台运行，刷新或离开网页不会取消。

## 下载、入库和恢复

Go 维护独立的持久执行队列，最多两首同时传输。每批固定音源版本、目标媒体库和真实目录，重复提交相同歌曲及请求音质会合并；自动和手动选择最终解析到同一音质时共享一次传输。重试失败、中断或取消项时使用当前配置，重新解析音频地址，保留原请求音质。

音频先写入下载目录内 `.singverse-downloads/`，限制 512 MiB、5 分钟总传输时间，检查空间、响应长度、格式和容器/帧边界；FLAC 校验音频区 CRC16，WAV/MP4/Ogg/MP3/ADTS 检查结构。它不是完整解码或音质鉴定。完整文件按「歌手 - 歌名」发布，同名时追加平台、标识与音质，已有文件不覆盖。发布与逐曲增量入库共用来源级扫描锁，避免完整扫描覆盖新索引。下载来源和原始目录元数据长期保留，以文件大小、修改时间核对素材，重扫沿用并保留有效人工订正。

默认尝试写入同名 LRC 和 JPEG/PNG/WebP 静态封面；附件失败不阻止音频入库，在记录中提示，伴奏继续使用音频工作台。取消排队、解析与传输中的项目；正在发布的完整文件完成入库。服务重启后核对发布中项目的 SHA-256 并补入库，清理未完成传输的私有临时文件并标记中断，排队项目继续执行。入库暂时失败时保留发布状态，后台重新核对。

下载记录提供逐曲状态、字节进度、附件提示、取消、重试及入库歌曲入口。后台任务中心仅投影下载批次和已有逐曲结果，业务深链接回到下载记录，执行操作仍在歌曲下载页。共享歌单继续由原歌单管理维护，读取歌单不会自动创建共享歌单。

## 运行边界和接口

基础下载不依赖 compute。Go 以独立 QuickJS worker 进程执行 LX 2.0 脚本，提供受控 HTTP、Buffer、MD5/SHA-256、AES/RSA、随机字节及定时器。worker 不提供文件、进程、线程、环境变量、数据库或 Node 模块访问。初始化 10 秒、歌曲解析 20 秒超时后终止 worker，每个运行时内存预算 256 MiB，最多同时运行 3 个；网络只能通过宿主受控 HTTP 桥。

脚本请求统一经 Go HTTP 桥，连接使用所有解析 IP 均为公网的 DNS 结果，每次重定向重新检查；禁止 HTTPS 降级，跨源剥离认证头，保持 TLS 验证，不使用环境代理。音源网络请求总时限 15 秒、响应最大 8 MiB。导入地址只允许 HTTPS，音频 URL 支持公网 HTTP(S)。脚本错误对外只返回固定类别，避免错误字符串带出密钥。

全部 `/api/v1/lx-downloads` 接口要求管理员会话；POST/DELETE 使用 CSRF，包括用 POST 表达的目录查询。请求只提交服务器缓存的歌曲标识和音质，客户端不能指定音频 URL、文件名或脚本解析载荷。

| 接口 | 作用 |
|---|---|
| `GET /status` | 目录、启用音源、支持平台与活动批次数 |
| `GET/POST /sources` | 列出或导入音源 |
| `POST /sources/{id}/activate`、`/test`；`DELETE /sources/{id}` | 启用、测试或移除 |
| `POST /catalog/search`、`/preview` | 搜索、歌单或专辑分页预览 |
| `GET /catalog/tracks/{id}`；`POST /catalog/states` | 刷新歌曲详情或读取缓存状态 |
| `GET /covers/{id}` | 受认证同源封面代理，不接受任意外部 URL |
| `GET/POST /batches`；`GET /batches/{id}`、`/items` | 提交与读取下载批次、逐曲明细 |
| `POST /batches/{id}/cancel`、`/retry` | 取消未完成项目或重试 |

字段与状态见 [OpenAPI](../contracts/openapi/singverse.openapi.json)，数据库使用两种方言配对的 `202611090003_lx_downloads.sql` 增量。所有业务 SQL 位于 `internal/downloads/sql/`，下载绑定使用单例行与事务锁并发保护。

实现参考 NoraMusic 的 `mobile/lib/features/library/application/track_download_service.dart`、音源运行桥和五平台目录适配，以及 [LX 自定义音源协议](https://lxmusic.toside.cn/desktop/custom-source)；Go 的脚本运行由隔离的 QuickJS worker 提供。专辑目录适配在本项目新增。公开目录接口可能变化；具体上游可用性、自备音源、NAS 权限与部署架构需分别验收，见[验证记录](verification.md)。
