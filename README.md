# SingVerse

家庭KTV多端基础工程：Flutter Android手机端、Android TV、独立NAS管理前端、Java业务主服务、Python计算节点，以及亲友H5欢迎页。亲友入口已调整为**微信扫一扫打开NAS网页**，不再使用小程序。当前仅工程骨架与管理基础界面，已增加单管理员登录与可选TOTP二次验证；已支持管理端本地/WebDAV 原始音视频在线播放；没有点歌、配对或聚会播放队列业务。

## 技术栈与目录

| 部分 | 技术 | 位置 |
|---|---|---|
| 手机App | Flutter 3.47.5 / Dart 3.13.4，Android优先 | `services/singverse-mobile` |
| 电视 | Kotlin 2.1.20 / Compose / TV Material 1.0.0 / Gradle 8.11.1 | `services/singverse-tv` |
| 业务主服务 | Java21 / Spring Boot 3.5.16 / MyBatis XML / Maven Wrapper 3.9.16 | `services/singverse-java-backend` |
| 计算节点 | Python 3.12+ / FastAPI 0.121.3 / Uvicorn 0.38.0；健康与能力探测骨架 | `services/singverse-compute` |
| 网页前端 | Vue 3 / TypeScript / Vite / Element Plus 管理端 + 原生亲友欢迎页，同一工程构建 | `services/singverse-frontend` |
| 数据库 | 默认SQLite，MySQL保留可选连接验证 | `compose.yaml`仅开发MySQL |

```text
services/
  singverse-frontend/      统一网页前端：亲友入口/party/，管理入口/admin/
  singverse-mobile/        Flutter手机端及Android宿主
  singverse-tv/            独立Android TV工程
  singverse-java-backend/  Java业务主服务
  singverse-compute/       内部计算服务，当前Python健康/能力探测
contracts/openapi/        业务API约定与计算节点内部探测契约
database/                 数据库约定
infra/docker/             应用容器与开发MySQL说明
compose.app.yaml          前后端应用容器，默认SQLite
docs/                     架构、开发、验证与research调研
.github/workflows/        singverse-frontend、singverse-mobile、singverse-tv、singverse-java-backend、singverse-backend
```

`services/`是本仓库统一工程目录，各模块使用`singverse-模块名`命名并独立构建。亲友点歌页面与NAS管理页面同属`singverse-frontend`；Java工程、Maven制品及Spring应用名统一为`singverse-java-backend`。目录迁移明细见[仓库布局](docs/repository-layout.md)。

统一前端以Vite多页面方式构建：`party/index.html`提供亲友欢迎页，`admin/index.html`加载Vue/Element Plus管理页面。管理端提供概览、系统状态、真实健康检查及失败重试。整个`services/singverse-frontend/dist`可交给Java或未来Go托管；浏览器通过同源HTTP访问主服务，不依赖Java内部类型。当前Java将产物打入JAR，生产不需要Node进程；Docker交付由Nginx前端容器与Java后端容器组成。详见[前端说明](services/singverse-frontend/README.md)与[托管契约](contracts/frontend-hosting.md)。

亲友H5继续使用本地HTML/CSS，入口为`/party/`；管理端直接托管在`/admin/`，Nginx对外映射到`/`；无CDN、微信JS-SDK、小程序AppID或微信网页登录。

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

可选连接计算节点：先执行 `make compute-run`，再以 `COMPUTE_NODE_URL=http://127.0.0.1:8090 ./mvnw spring-boot:run` 启动Java。节点地址缺省为空，Java启动和Actuator健康不依赖节点在线；当前内部客户端仅供后续业务调用，尚无管理HTTP接口或任务派发。Python是内部执行层，不增加客户端菜单；功能通过曲库、媒体转换和AI等既有流程接入。计算节点 `/health` 为进程健康，`/internal/v1/capabilities` 当前返回空能力列表。详见[计算节点说明](services/singverse-compute/README.md)。

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

已实现欢迎页、NAS管理基础界面与健康查询、构建/静态分析/基础测试、两个网页入口托管、健康检查、两种数据库真实连接验证、Python节点健康/能力探测、Java内部探测客户端、根命令和六个CI工作流（含Docker交付检查）。后续服务端转解码、AI伴奏分析等重计算统一由Python节点执行；Java保留授权、曲库、会话、队列与业务数据库职责。已实现管理员登录、改密与MFA、媒体来源配置管理、文件名共享规则库与解析方案管理页及SQL迁移。未实现扫码配对、歌曲、队列、播放、扫描、转码、麦克风、歌曲索引等业务表、Go、线上部署。

Taro工程、锁文件、相关Make目标和工作流已移除。Flutter/TV包名仍为`com.mynora.singverse.mobile`与`.tv`，未来iOS扩展见手机端README。

常见问题：Java版本错误检查`JAVA_HOME`；Android设置`ANDROID_HOME`；局域网页打不开检查手机网络/隔离、NAS端口、服务绑定和微信提示，不全局关闭证书验证；MySQL检查导出变量、健康状态与已有卷账号。

详见[开发指南](docs/development.md)、[架构](docs/architecture.md)、[数据库策略](docs/database-strategy.md)、[验证记录](docs/verification.md)和[产品需求文档（PRD）](docs/research/PRD.md)。

媒体来源已支持“曲库 → 媒体库管理”：本地目录与 WebDAV 分别配置、编辑和移除，详见[媒体库管理](docs/media-library.md)。支持选择完整解析方案、本地目录手动扫描、WebDAV 后台扫描并保存歌名/歌手等文件名数据；OpenList 网盘仅列目录、不下载视频，单来源支持 10 万首及进度/取消。

曲库菜单包含“解析规则”和“解析方案”：前者维护模板/高级正则及引用情况，后者组合共享规则、启停排序并输入文本预览。配置全局保存，旧配置映射默认方案，媒体来源可引用方案，本地扫描按启用规则顺序解析并保存歌曲元数据。详见[规则说明](docs/filename-rules.md)。

Java 后端业务 SQL 统一使用 MyBatis Mapper XML，仓库后续开发遵守根 `AGENTS.md` 的 SQL 约定；dbmate 继续管理数据库迁移。详见[数据库策略](docs/database-strategy.md#mybatis-xml-约定)。

“曲库 → 歌曲管理”集中查看各媒体库扫描生成的歌曲，支持来源、文件名与复核状态筛选，以及解析详情。扫描完成后自动跳转对应来源列表；数据更新仍通过媒体库重扫完成。

歌曲管理现已支持单曲、选中及全库的元数据刮削与候选审核，功能和自动写入默认关闭；使用入口、字段保护、批次恢复和平台限制见[元数据刮削](docs/metadata-scraping.md)。

歌曲分类现支持后台词表维护、人工/批量打标、可配置文本 AI 分类任务、按维度自动应用、审核与历史撤回；模型默认关闭。使用方法与边界见[歌曲分类](docs/song-classification.md)。
