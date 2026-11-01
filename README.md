# SingVerse

家庭KTV多端基础工程：Flutter Android手机端、Android TV、独立NAS管理前端、Java业务主服务、Python计算节点，以及亲友H5点歌页。亲友入口已调整为**微信扫一扫打开NAS网页**，不再使用小程序。当前已具备曲库管理及多端点唱基础，已增加单管理员登录与可选TOTP二次验证；已支持管理端本地/WebDAV 原始音视频在线播放；已增加电视设备授权、原始媒体直放、聚会队列与亲友 H5 扫码点歌；已支持计算节点转码、全曲库音轨角色及可选离线伴奏分离，真实电视/微信/NAS 验收见验证记录。

## 技术栈与目录

| 部分 | 技术 | 位置 |
|---|---|---|
| 手机App | Flutter 3.47.5 / Dart 3.13.4，Android优先 | `services/singverse-mobile` |
| 电视 | Kotlin 2.1.20 / Compose / TV Material 1.0.0 / Gradle 8.11.1 | `services/singverse-tv` |
| 业务主服务 | Java21 / Spring Boot 3.5.16 / MyBatis XML / Maven Wrapper 3.9.16 | `services/singverse-java-backend` |
| 计算节点 | Python 3.12+ / FastAPI 0.121.3 / Uvicorn 0.38.0；媒体探测、转码及可选音轨分析/伴奏分离 | `services/singverse-compute` |
| 网页前端 | Vue 3 / TypeScript / Vite / Element Plus 管理端 + Vue 亲友点歌页，同一工程构建 | `services/singverse-frontend` |
| 数据库 | 默认SQLite，MySQL保留可选连接验证 | `compose.yaml`仅开发MySQL |

```text
services/
  singverse-frontend/      统一网页前端：亲友入口/party/，管理入口/admin/
  singverse-mobile/        Flutter手机端及Android宿主
  singverse-tv/            独立Android TV工程
  singverse-java-backend/  Java业务主服务
  singverse-compute/       内部媒体探测、转码与音频计算服务
contracts/openapi/        业务API约定与计算节点内部探测契约
database/                 数据库约定
infra/docker/             应用容器与开发MySQL说明
compose.app.yaml          前后端应用容器，默认SQLite
docs/                     架构、开发、验证与research调研
.github/workflows/        singverse-frontend、singverse-mobile、singverse-tv、singverse-java-backend、singverse-backend
```

`services/`是本仓库统一工程目录，各模块使用`singverse-模块名`命名并独立构建。亲友点歌页面与NAS管理页面同属`singverse-frontend`；Java工程、Maven制品及Spring应用名统一为`singverse-java-backend`。目录迁移明细见[仓库布局](docs/repository-layout.md)。

统一前端以Vite多页面方式构建：`party/index.html`加载亲友点歌 Vue 应用，`admin/index.html`加载Vue/Element Plus管理页面。管理端提供概览、系统状态、真实健康检查及失败重试。整个`services/singverse-frontend/dist`可交给Java或未来Go托管；浏览器通过同源HTTP访问主服务，不依赖Java内部类型。当前Java将产物打入JAR，生产不需要Node进程；Docker交付由Nginx前端容器与Java后端容器组成。详见[前端说明](services/singverse-frontend/README.md)与[托管契约](contracts/frontend-hosting.md)。

亲友H5使用本地构建的 Vue/TypeScript 与 CSS，入口为`/party/`；管理端直接托管在`/admin/`，Nginx对外映射到`/`；无CDN、微信JS-SDK、小程序AppID或微信网页登录。

后端[Nginx配置](services/singverse-java-backend/nginx/README.md)已提供静态页面映射和API转发约定。前端请求统一带`/api`前缀，健康检查为`/api/health`，业务接口后续使用`/api/v1`；代理保留该前缀。

## 管理员账号

首次启动账号密码为 `admin/admin`。登录后到「系统管理 → 系统参数」修改密码、绑定认证器或管理恢复码；默认密码只提示修改。公网访问前请配置HTTPS并修改默认密码。恢复命令、备份及接口见[账号与MFA说明](docs/authentication.md)。

## Docker快速测试

```sh
make docker-up
make docker-check
# 停止并保留数据卷
make docker-down
```

构建启动只需Docker与Compose；检查脚本另需Python3。管理页访问 http://localhost:18080/ ，点歌欢迎页访问 http://localhost:18080/party/ 。默认仅本机可访问，后端不发布端口，SQLite使用独立命名卷。端口、局域网访问和日志见[Docker说明](docs/docker.md)。

## 环境与命令

安装Node.js 22.19+、Flutter、JDK21、Python 3.12+、Android SDK35/36及对应工具。Node用于管理前端构建，依赖通过npm锁定；Maven/Gradle使用各工程Wrapper，MySQL验证需要Docker/Compose。Flutter Android当前minSdk24；TV当前minSdk23，研究中的Android5/API21目标尚待后续实现和实机验证。

```sh
make help
make frontend-check
make frontend-dev       # 可选：127.0.0.1:5173/admin/，代理到已启动的Java
make mobile-check
make mobile-build
make web-check
make tv-build
make java-backend-test
make db-sqlite-check
make compute-setup  # 首次创建项目虚拟环境、安装锁定依赖
make compute-check
make backend-check # Java/Python整体联动验证；需要JDK21
make compute-run    # 单独终端运行，默认127.0.0.1:8090
```

构建管理端后启动服务（以下从仓库根目录执行）：

```sh
make frontend-build
cd services/singverse-java-backend
./mvnw spring-boot:run
# 或先打包再启动
./mvnw -B package
java -jar target/singverse-java-backend-0.1.0-SNAPSHOT.jar
```

在“系统设置 → 性能节点配置”管理计算服务。应用 Compose 自动提供同套 `singverse-compute` 地址与共享凭据；独立部署可配置地址、加密凭据和媒体路径映射，或选择暂不使用。连接检查验证身份、认证及实际能力，不发起媒体任务。节点离线不影响 Java/管理端健康，扫描保留文件名回退；音视频标签读取由 Python 执行。独立部署见[计算节点说明](services/singverse-compute/README.md)。

管理中心访问 `http://localhost:8080/admin/`，亲友欢迎页 `http://localhost:8080/party/`，健康检查 `http://localhost:8080/api/health`。直接执行Maven也需先构建前端；缺少产物会明确失败。后续在NAS实际部署后，手机必须使用其可达地址，例如 `http://<NAS局域网IP>:8080/`；二维码不能写localhost或容器内部IP。

**当前没有在TV生成二维码，也没有配对/点歌功能。** 微信扫码是确定的后续入口，需Android/iOS微信真机验证导航、安全提示、网络权限和前后台恢复。普通浏览器打开成功不等于微信扫码已验证。

两个入口的页面、脚本与样式随JAR交付；独立admin CI检查前端，Java CI验证H5与管理端HTTP及资源。无需小程序开发者工具、上传或发布流程。compose.yaml提供开发MySQL；compose.app.yaml提供Nginx与Java应用容器，本地运行不代表NAS实机已验收。

## 数据库

默认SQLite：`SQLITE_PATH`缺省`./data/singverse.db`，相对于服务进程工作目录，必要父目录自动创建；应用启动执行真实`SELECT 1`并通过dbmate迁移账号、媒体来源及解析规则配置表。默认从服务目录启动时数据在`services/singverse-java-backend/data/`。

```sh
(cd services/singverse-java-backend && SQLITE_PATH=/tmp/singverse-dev/app.db ./mvnw spring-boot:run)
```

MySQL开发验证：

```sh
cp -n .env.example .env
# 新建时编辑.env，替换两个密码；已有本地配置不要覆盖
make db-mysql-up
set -a
. ./.env
set +a
make db-mysql-check
(cd services/singverse-java-backend && SPRING_PROFILES_ACTIVE=mysql ./mvnw spring-boot:run)
```

Spring Boot不会自动读取`.env`；Compose会读取，主机Java必须导出变量。MySQL默认仅绑定`127.0.0.1:3306`，使用命名卷；更改初始化密码不会自动更新已有卷账号。停止使用`docker compose stop mysql`，不自动删除数据卷。

首次执行`make compute-setup`并正确配置环境后，`make verify`依次验证手机、NAS管理前端、H5、TV、Java/Python整体联动及SQLite/MySQL。任一步失败返回非零；不会跳过数据库或自动删除卷。

## 当前边界与文档

已实现欢迎页、NAS管理基础界面与健康查询、构建/静态分析/基础测试、两个网页入口托管、健康检查、两种数据库真实连接验证、Python节点健康/能力探测、Java内部探测客户端、根命令和六个CI工作流（含Docker交付检查）。服务端转解码、可选本地模型的双轨识别与单轨伴奏分离统一由 Python 节点执行；Java保留授权、曲库、会话、队列与业务数据库职责。已实现管理员登录、改密与MFA、媒体来源配置管理、文件名共享规则库与解析方案管理页及SQL迁移。已继续实现扫码配对、曲库扫描、歌曲与歌手管理、共享歌单、聚会队列、原件及转码播放、离线兼容版本；各模块的实际验收范围见验证记录。手机端现已增加管理员登录、NAS 选歌、本机练唱/录唱、混音回听、本机作品与导出、后台媒体/录音服务、收藏历史及扫码承载同一 H5。K 歌改版仍有宿主真实麦克风输入未通过，不能视为已完成设备验收，详见手机端 README 与验证记录。Go 等后续能力不在本轮范围。

Taro工程、锁文件、相关Make目标和工作流已移除。Flutter/TV包名仍为`com.mynora.singverse.mobile`与`.tv`，未来iOS扩展见手机端README。

常见问题：Java版本错误检查`JAVA_HOME`；Android设置`ANDROID_HOME`；局域网页打不开检查手机网络/隔离、NAS端口、服务绑定和微信提示，不全局关闭证书验证；MySQL检查导出变量、健康状态与已有卷账号。

详见[开发指南](docs/development.md)、[架构](docs/architecture.md)、[数据库策略](docs/database-strategy.md)、[验证记录](docs/verification.md)和[产品需求文档（PRD）](docs/research/PRD.md)。

媒体来源已支持“曲库 → 媒体库管理”：本地目录与 WebDAV 分别配置、编辑和移除，详见[媒体库管理](docs/media-library.md)。支持选择完整解析方案、本地目录手动扫描、WebDAV 后台扫描并保存歌名/歌手等文件名数据；OpenList 网盘仅列目录、不下载视频，单来源支持 10 万首及进度/取消。

曲库菜单包含“解析规则”和“解析方案”：前者维护模板/高级正则及引用情况，后者组合共享规则、启停排序并输入文本预览。配置全局保存，旧配置映射默认方案，媒体来源可引用方案，本地扫描按启用规则顺序解析并保存歌曲元数据。详见[规则说明](docs/filename-rules.md)。

Java 后端业务 SQL 统一使用 MyBatis Mapper XML，仓库后续开发遵守根 `AGENTS.md` 的 SQL 约定；dbmate 继续管理数据库迁移。详见[数据库策略](docs/database-strategy.md#mybatis-xml-约定)。

“曲库 → 歌曲管理”集中查看各媒体库扫描生成的歌曲，支持来源、文件名与复核状态筛选，以及解析详情。扫描完成后自动跳转对应来源列表；数据更新仍通过媒体库重扫完成。

歌曲管理现已支持单曲、选中及全库的元数据刮削与候选审核，功能和自动写入默认关闭；使用入口、字段保护、批次恢复和平台限制见[元数据刮削](docs/metadata-scraping.md)。

歌曲分类现支持后台词表维护、人工/批量打标、可配置文本 AI 分类任务、按维度自动应用、审核与历史撤回；模型默认关闭。使用方法与边界见[歌曲分类](docs/song-classification.md)。

本地歌曲扫描支持只读提取常见音视频文件内嵌歌名、歌手、专辑和发行日期，文件标签优先，缺失回退文件名；Docker的独立singverse-compute镜像包含Python/FFmpeg，Java镜像不包含。WebDAV仍仅索引文件名。详见[后端说明](services/singverse-java-backend/README.md)。

歌手库入口为管理端「曲库管理 → 歌手库」：随歌曲自动汇总，首次自动抓图，支持上传头像/背景图；在歌手详情订正歌曲后自动同步，无需维护人物档案。使用与数据设计见 [歌手库说明](docs/artist-library.md)。

共享歌单可在「曲库管理 → 歌单管理」手动或通过 AI 建议创建，并同步显示到 TV「歌单」入口。详见 [歌单说明](docs/playlists.md)。

视频兼容播放、离线版本、工具安装及硬件边界见 [转码说明](docs/transcoding.md)。

## 转码硬件加速设置

后台入口：**系统设置 → 性能节点配置 → 转码与硬件加速**。点击“读取硬件加速设置”，选择方式，可测试所选方式、探测所有编码器并保存。保存后对新任务生效；正在运行的任务不切换编码器。指定硬件方式时保存前自动执行短样本编码，失败保留原设置。

`COMPUTE_HARDWARE_BACKEND` 是 **compute 进程启动时的默认加速方式**：

| 值 | 用途 |
|---|---|
| `auto`（默认） | 自动尝试探测通过的硬件编码器，无可用硬件时回退 CPU |
| `software` | 关闭硬件加速，仅 CPU 软件编码 |
| `qsv` | Intel Quick Sync，Linux / Windows |
| `vaapi` | Linux Intel / AMD VA-API |
| `nvenc` | Linux / Windows NVIDIA NVENC |
| `amf` | Windows AMD AMF |
| `videotoolbox` | macOS VideoToolbox |
| `rkmpp` | Linux Rockchip RKMPP |

原生节点示例（先按[转码说明](docs/transcoding.md)配置媒体目录、凭据、缓存目录和工具）：

```sh
COMPUTE_HARDWARE_BACKEND=videotoolbox make compute-run
```

**优先级：后台保存的选择 > `COMPUTE_HARDWARE_BACKEND` > 默认 `auto`。** 后台选择“遵循部署配置”恢复环境变量的值。环境变量变化需重启 compute；后台保存无需重启。后台选择保存在计算节点缓存卷根目录的 `hardware-settings.json`，重建容器且保留缓存卷时继续生效；更换计算节点后使用新节点自己的设置。

Docker 必须在部署时配置设备、驱动和组权限。Intel/AMD 使用 `compose.compute.dri.yaml`，NVIDIA 使用 `compose.compute.nvidia.yaml`，Rockchip 使用 `compose.compute.rockchip.yaml`；覆盖文件会向容器传入对应 `COMPUTE_HARDWARE_BACKEND`。仅把变量写入根 `.env` 不会自动传入未声明该变量的容器；使用上述覆盖文件或显式增加 Compose environment。后台不能自动透传 GPU、安装驱动或赋予设备权限。

已增加按素材试跑的硬件解码、零拷贝与 GPU 色调映射；无法使用的阶段会明确回退。Dolby Vision 单层 P5/P8 使用 RPU 元数据转换至 SDR，普通 HDR 支持不自动等于 DV 支持。短样本成功不代表其他硬件或持续负载已验收；详情见[转码说明](docs/transcoding.md)和[验证记录](docs/verification.md)。

macOS + Docker Desktop 使用原生 GPU 节点：先确保现有应用已启动并执行过 `make compute-setup`、`make transcode-tools`，再从仓库根目录运行：

```sh
services/singverse-compute/.venv/bin/python scripts/macos-compute.py start
docker compose -f compose.app.yaml -f compose.compute.macos.yaml up -d singverse-java-backend singverse-frontend
docker compose -f compose.app.yaml stop singverse-compute
SINGVERSE_MACOS_COMPUTE=1 make docker-check
```

后台性能节点连接方式使用“自动识别”。Java 经 `host.docker.internal:18090` 连接仅监听回环的 Mac 节点，复用现有内部凭据，使用 VideoToolbox；WebDAV 仍通过 Java 读取。可选本地媒体目录 `data/macos-compute/media` 按相同绝对路径只读映射到 Java，新建本地来源时填写该绝对路径。节点缓存与凭据也保存在被忽略的 `data/macos-compute`，均不放入媒体目录。启动器使用当前用户的 launchd 保持节点运行，并在 `~/Library/LaunchAgents` 注册指向本地运行配置的登录启动项；保留本仓库及虚拟环境路径。`scripts/macos-compute.py status` 查询、`stop` 停止节点并移除本启动器自己的登录启动项。Java HTTP 入口需为本机默认 18080；其他端口部署请调整启动器的内部来源地址。重启应用继续带上 macOS 覆盖文件，以保留此连接配置。

Mac 原唱／伴奏使用原生 CPU 或 MPS：显式执行 `make compute-audio-setup` 安装固定依赖及权重后，用 `scripts/macos-compute.py restart` 加载音频配置，再到“曲库 → 原唱与伴奏 → 节点与模型”对 CPU／MPS 试跑。启动器默认 4GiB 音频预算，可显式指定 `--audio-memory-mib 2048`，后续重启保留该预算。本机 M2 已通过 MPS 分块卷积、CPU 数值对照及 8／60 秒批量分离验证；不启用隐式 CPU 算子回退。复验可执行 `make macos-audio-check`，完整说明见 compute README。

可选真机验证：`make transcode-fixtures` 下载校验固定的 Jellyfin 官方 Dolby Vision 测试素材（不进入生产镜像）；设置仓库媒体工具 PATH 后，可在目标设备运行 `services/singverse-compute/.venv/bin/python services/singverse-compute/tests/smoke/verify_hardware.py --backend videotoolbox --fixtures .tools/transcode-fixtures`。其他设备替换 backend；输出会区分实际硬解、CPU 回传、零拷贝和 GPU 色调映射。

## 原唱与伴奏

双轨扫描后后台探测与保守识别；不确定结果保持待确认，手机、TV 与已加入聚会的亲友 H5 可纠正同一份全曲库音轨角色。单轨由管理员在歌曲详情按需生成独立伴奏，原件只读，视频时间线保持不变。WebDAV v2 分享只携带角色元数据，分离音频由接收方自行生成。

模型默认关闭；需要时先 `make compute-setup`、`make compute-audio-setup`，设置本地模型路径并重启 compute。固定版本、Compose 可选部署、资源预算和真实模型冒烟命令见 [计算节点 README](services/singverse-compute/README.md#原唱识别与伴奏分离)。真实歌曲效果与手机/TV/NAS 同步尚需验收，不能以合成音冒烟替代。

伴奏管理入口：**曲库 → 原唱与伴奏**。支持跨页/全部筛选结果批量生成、持久任务及取消/重试；“节点与模型”可下载/离线导入固定模型并执行后端试跑。部署CPU、NVIDIA CUDA、AMD ROCm及Mac MPS的方式和当前Intel GPU/RKNN限制见计算节点README；实际只有一个计算节点，不做多节点分配。

计算节点现提供 CPU（amd64/arm64）、CUDA、ROCm、OpenVINO 镜像构建目标及正式 release 发布工作流；模型独立下载／离线导入。部署选择见 [compute 说明](services/singverse-compute/README.md)。GPU 和设备效果必须单独验收，RKNN 尚无合格模型。
