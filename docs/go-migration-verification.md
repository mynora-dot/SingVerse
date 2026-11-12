# Go 迁移专项验证

> 本文记录旧交付版本的历史结果；旧 Nginx、打包及回退命令已退休。当前 Docker 入口见 `infra/docker/README.md`，不将下列结果视为重建交付的验证。

## 2026-11-09 十万首曲库 HTTP 查询

在本机 macOS arm64、Go 1.27.1 上运行真实 Go 服务与隔离 SQLite。测试通过实际管理员 Cookie/CSRF 登录、电视 Bearer 授权、邀请及亲友加入协议建立身份，批量写入 100000 首合成歌曲，然后调用生产 HTTP 处理器。没有读取或修改用户曲库，也没有创建媒体原件。

复现入口为 `services/singverse-go-backend/internal/party/performance_test.go` 中的 `TestHundredThousandSongHTTPQueries`。从仓库根运行：

```sh
SINGVERSE_PERF_100K=1 go -C services/singverse-go-backend test ./internal/party -run TestHundredThousandSongHTTPQueries -v -count=1
```

该测试仅在显式设置环境变量时执行，普通单元测试不会加载十万首数据。断言覆盖总量、分页条数、拼音搜索跨页不重复、数字点歌码定位具体歌曲，以及末页结果。以下为本轮首个成功实测，耗时包含本机 HTTP 往返与 JSON 解码，不是纯 SQL 基准：

| 操作 | 实测耗时 | 结果 |
|---|---:|---|
| 单事务写入 100000 首 SQLite 歌曲 | 1.743 秒 | 提交成功 |
| 管理员曲库首页 | 15.036 毫秒 | 总数 100000，返回 20 首 |
| 管理员中文关键词“晴天” | 28.080 毫秒 | 总数 1000，返回 20 首 |
| TV 首次构造曲库索引及读取首页 | 2.810 秒 | 总数 100000，返回 20 首 |
| TV 已构造索引后拼音 `zhoujielun` 搜索 | 14.145 毫秒 | 总数 1000，返回第 2 页 20 首 |
| 亲友 H5 已构造索引后拼音搜索 | 9.488 毫秒 | 总数 1000，返回第 3 页 20 首 |
| TV 点歌码 `099900` | 36.515 毫秒 | 唯一命中 `perf-099900` |
| TV 第 5000 页 | 23.024 毫秒 | 总数 100000，返回 20 首 |

整项测试通过，测试进程报告 5.535 秒。首轮发现的后台重复整库工作已修正：歌单列表在同一请求复用一个曲库快照；歌手图片后台任务最多每 30 秒重新同步整库，并仅保留每位歌手最多 3 首验证参考；TV 首页复用现有索引，变更提交后通过失效回调更新。首次 TV 索引仍是线性构造，其耗时不能作为已构造索引后的查询延迟。

随后将仅包含文件名解析信息的歌曲从“结构体编码成 JSON 再解码为 map”改为直接构造相同公开投影，再次执行同一隔离用例通过。第二次测试总耗时为 5.491 秒（测试体 4.84 秒），写入为 2.162 秒；管理员首页 24.155 毫秒、中文搜索 35.933 毫秒、TV 首次索引 2.186 秒、TV 拼音搜索 2.820 毫秒、H5 拼音搜索 1.953 毫秒、点歌码 6.048 毫秒、末页 15.339 毫秒。该比较包含调度与缓存波动，不将某次延迟降低视为固定吞吐承诺。

## 2026-11-09 路由与图片存储契约

`internal/app/routes_contract_test.go` 使用完整应用的真实 Gin 路由表，对照 `contracts/go-migration-baseline.json`，276 个公开操作全部匹配；实际表包含 281 个注册。检查规范化路径参数名称，但保留 HTTP 方法与路径层次，不通过增加空处理器满足清单。

本轮参数审计补齐 TV/H5 的重复或逗号分隔 `tagIds`、Spring 既有布尔字面值、TV 图片类型的 400 响应、UTF-16 标题长度，以及管理员图片删除必填 `version` 和图片上传 MIME 的 415 响应。电视授权撤销沿用 Java 的 200 空响应，当前 OpenAPI 同样声明 200。

实际 SQLite/HTTP 断言覆盖分类标签封面、歌手图片及 TV 首页 banner 的上传、持久化、GET 回读、JPEG 内容与 `nosniff`；TV banner 另检查设备授权。播放资源协商检查设备身份隔离、歌曲不存在时的 404，以及执行层 410 业务状态透传。认证、TV/H5、AI、目录和下载模块均运行过 race 回归；新增参数兼容断言另执行定向回归，不用路由清单覆盖代替行为断言。

## 2026-11-09 Java / Go 相同 HTTP 脚本

新增 `scripts/check-backend-contract.py`，仅使用 Python 标准库 `urllib`、`http.cookiejar` 等。传入 `--base-url` 与 `--isolated`，入口限制为 loopback HTTP；登录后确认初始管理员和空媒体来源，再执行有状态验证。只允许对本次新建、独占的测试部署运行：

```sh
python3 scripts/check-backend-contract.py --base-url http://127.0.0.1:<随机测试端口> --isolated
```

本轮同一脚本分别通过两端，每端 **166 条断言**：

| 端 | 实际启动对象 | 结果 |
|---|---|---|
| Java 对照 | 当前缓存 `singverse-java-backend-0.1.0-SNAPSHOT.jar`，JBR 25.0.3，临时 SQLite / 密钥 / 衍生目录，随机 loopback 端口 | 退出码 0，166 条断言通过 |
| Go | 当前源码新构建的本机 `cmd/singverse` 二进制，临时 SQLite / 密钥 / 衍生目录，另一随机 loopback 端口 | 退出码 0，166 条断言通过 |

检查涵盖公开 health 仅暴露状态、`/admin/` 与 `/party/` HTML 和真实 JavaScript 资源、管理员登录及 HttpOnly/SameSite Cookie、缺失 CSRF 与跨 Origin 拒绝、登出撤销旧 Cookie、TV 申请/批准/轮询、设备和亲友身份、Cookie 与 Bearer 授权边界、解绑后设备/亲友失效、错误 Problem Details 结构及非法分页参数。

Java 首次启动在本机并发构建期间超过 70 秒预算，未计通过；随后为测试 JVM 设置 2 个可见 CPU、限制分层编译并采用 180 秒启动预算后成功。脚本初版错误地将媒体来源分页响应按数组判断，双方均失败；依据真实控制器修正为 `items/totalElements`，在两套全新临时库重跑后才得到上述通过结果。没有修改 Java 行为以适应脚本。所有本轮子进程均已终止，临时数据库、密钥和目录均清理，未访问现有部署或真实媒体。

该脚本覆盖共同核心边界，不能取代 276 个操作的完整业务回归、MySQL、浏览器视觉、NAS 或客户端真机验收。23 个后台定时入口另见 `../contracts/go-scheduled-migration.md`，不把路由覆盖当作后台流程验证。

这些结果验证本机 SQLite 的合成索引查询，不代表 MySQL、真实 NAS 网络媒体、微信/电视/手机真机、其它 CPU 架构或持续负载验收。没有将此前 Java 或外部平台实测结果合并为本轮 Go 通过。

## 2026-11-09 最终源码、数据库与执行隔离

最终工具链固定为 Go **1.27.3**，staticcheck **0.8.2**、govulncheck **1.9.0**，工具和缓存均位于仓库 `.tools/`。`scripts/check-go-backend.sh` 实际退出 0：gofmt、`go vet ./...`、staticcheck、govulncheck 和 `go test -race ./...` 全部通过。漏洞扫描输出 `No vulnerabilities found.`；早期工具链及依赖扫描发现的问题已通过固定修复版本解决，不将首次失败计为通过。

`make db-fresh-check` 实际通过空库初始化、重复迁移、增量数据保留、回滚重建及约束检查（82 张表、66 个索引）。没有修改两种数据库的已发布初始迁移。

`infra/docker/check-go-mysql.py` 在独占的 MySQL 8.4.8 容器、随机 loopback 端口及逐测试随机数据库上执行完整 `-race ./... -run MySQL -count=1`，最终退出 0。实际覆盖管理员 HTTP 改名改密/撤销/MFA 与恢复码并发一次性消费、独立连接修订竞争、业务回滚、后台投影、元数据人工字段保护及分片导入恢复、下载发布、缓存范围读取、音轨批次、HLS/离线版本与所有者边界，以及跨本机锁路径的 MySQL 服务端部署锁。首次执行暴露 MySQL 表与连接排序规则混用；修复四类投影 SQL 的字节比较并增加真实 MySQL 回归后，完整重跑通过。该检查没有使用 SQLite 代替 MySQL，也没有访问现有部署数据库。

最终 Go 1.27.3 下再次执行十万首 SQLite HTTP 用例通过（测试体 4.49 秒、包报告 5.125 秒）：写入 2.053 秒；管理员首页 39.758 毫秒、中文搜索 31.177 毫秒、TV 首次索引 2.017 秒、TV 已构造索引后拼音 3.124 毫秒、H5 拼音 2.280 毫秒、点歌码 6.636 毫秒、末页 17.688 毫秒。该合成单次运行不构成 NAS 或持续负载吞吐承诺。

真实 LX 子进程竞态测试通过，包含初始化无限循环强制终止、调用方取消、超过 256 MiB 的 JS 分配拒绝、递归/定时器边界、Promise/HTTP/异步回调、三个 worker 并发上限及关闭。HTTP 桥检查私有地址零请求、固定 DNS、公网目标校验、禁用环境代理及跨源跳转敏感头移除。256 MiB 仅为 JavaScript 分配额度，不能解释为整个进程的 RSS 限制。

`scripts/check-go-recovery.py` 使用当前原生 Go 二进制与真实 PTY，**74 条断言通过**：拒绝运行中部署锁和非终端输入，要求 `RESET` 与两次隐藏口令；恢复后旧会话失效、MFA 清除，新用户名/口令可登录，业务数据和凭据主密钥保持。测试仅使用临时数据库、衍生目录及独占子进程；没有重置现有管理员。

## 2026-11-09 真实 Python / FFmpeg 往返

`scripts/check-go-compute.py` 在 Go 1.27.3 下启动项目 Python 服务、真实 FFmpeg 与隔离 Go 应用，集成测试带 `-race` 实际通过（测试体 3.85 秒、包报告 5.999 秒）。覆盖 RSA 3072 发现/配对及认证回源、本地和 WebDAV 标签读取与撤销、音轨探测、fMP4 HLS、带 SHA 校验的导出回传与 Go 缓存发布；使用 ffprobe 验证 H.264/AAC 双音轨，另检查离线 MP4 导入和范围读取。媒体原件 SHA 保持不变。

该往返验证 CPU 软件路径和内部协议；不代表 GPU 硬解、零拷贝、色调映射或 NAS 上的硬件验收。

Python 完整回归实际为 223 项、8 项显式跳过，退出 0。可选模型及 GPU 用例的跳过不计为通过；随后显式设置 `SINGVERSE_TEST_SEPARATION_APP_BINARY`，单独运行真实 Go/Python DSP 分离专项，通过（29.254 秒），覆盖预览、批量任务、FLAC 导出回传、声道确认与伴奏激活、原始文件哈希及时间线。Mac/MPS 专项已改用原生 Go 二进制，仍要求真实 Apple GPU 与本地模型，本轮未启用。音轨 API 描述调整同步更新 compute OpenAPI，并单独通过 5 项契约检查。

## 2026-11-09 Docker 认证与错误边界

现有 `make auth-docker-check` 已使用 Go 镜像在新随机 Compose 项目与全新专属卷实际通过（最终重跑 42.385 秒）。检查改密后旧口令拒绝、TOTP 绑定、容器重建后口令/MFA/主密钥保留、恢复码一次性消费，以及 Linux 容器的真实 TTY 管理员恢复。恢复时口令不回显；恢复后旧 Cookie 失效、MFA 与恢复码清除，新口令可登录，主密钥保持。只清理该次独占项目创建的卷，没有执行现有应用或旧 Java 项目的 `down -v`。

部署检查发现的未知 API 空 404 已修复为 `application/problem+json`；真实管理员 Cookie/新 CSRF 的 GET 和 POST 用例均验证 404、`status`、`type`、`title` 和 `instance`。静态资源缺失继续保持真实 404。该修复后重新通过完整 Go 严格检查，且重新构建两种架构镜像。

## 2026-11-09 两种架构与完整 Compose

实际构建 Linux ARM64、AMD64 的 CGO-free Go 后端及 Nginx 前端镜像。隔离容器检查各执行完整 166 条共同 HTTP 契约；交付断言另验证不可达的可选 compute 不影响 health、同容器重启保留账号/会话/歌单/主密钥、运行镜像没有 JVM/Node/Go 编译器及 ELF 架构。ARM64 为本机 ARM 主机上的 Linux VM 原生执行，AMD64 由 Docker Desktop Rosetta 模拟执行，不能记为原生 AMD64 或 NAS 真机验收。Rosetta 下 `/proc/1/exe` 指向仿真器，检查同时核对真实 Go ELF、PID 1 命令行与映射文件，不跳过架构断言。

该轮精确构建上下文重建后，从 `infra/docker/check-go-container.py` 入口重跑：ARM64 为 166 条契约加 51 条交付断言，3.521 秒；AMD64 为 166 加 52 条，4.244 秒，均通过。ARM64 Go/Nginx 镜像 ID 分别为 `sha256:bff448cccc9d683ce2f810933a5b5652aa710b73253f868cb09495a786ff09d4` / `sha256:004ca0da35183ef4591a35d77f0a6dfb8f0e0a42342e12ab385fde71ca950cf2`，AMD64 对应下文 11 月 9 日候选离线包 ID。该轮镜像已被 11 月 10 日的亲友权限修复镜像替代。各次独占容器与网络标签检查均为空。

现有 `infra/docker/check-docker-app.py` 在新随机 Compose 项目中实际通过，固定当前源码构建的 Go、Nginx、compute 镜像，不拉取或覆盖旧 `local` 镜像。覆盖三个页面入口及 18 个资源、登录登出、health、20 KiB 非法封面确实到达 Go 格式校验、AUTO 节点认证和双向 callback，以及未知 GET/POST API 的 JSON 404。当前 compute 镜像的真实 FLAC/MP3/M4A/MP4/MKV HTTP 回源标签、离线转码、SHA 校验和原件只读检查也通过。测试项目的容器、网络与七个新建卷均已清理，原项目和旧卷没有操作。

完整检查首次拒绝本机旧 `compute:local` 镜像的 `media-tags` 能力名称；随后从当前 Dockerfile 另建测试标签，实际验证当前 `media-remote-tags` 协议并重跑通过。镜像源网络超时通过相同固定摘要的镜像源完成构建，没有修改 TLS 校验或放宽能力断言。

## 2026-11-09 构建上下文与源码部署包

`make docker-context-check` 对根 `.dockerignore` 回退入口及 Go Dockerfile 同目录、同字节的专用忽略规则分别执行真实 scratch `COPY .`，导出 tar 后核对完整清单。最终根规则为 1227 个文件、13071794 字节，Go 专用规则为 1147 个文件、11877448 字节；必需构建输入齐全，均无 `.git`、`.tools`、`node_modules`、`.venv`、业务数据、密钥、产物或 Java 工程。根兼容文件与 infra 维护源逐字节一致。

早期检查曾发现 `!services/` 等父目录重包含使缓存和依赖整树进入上下文，导出超时，不计为通过。先用独占小夹具验证 Docker 可按精确否定路径穿越父目录，再删除父目录重包含、保留精确文件及许可子树，并在末尾显式拒绝运行数据和产物后，完整双场景审查通过。

源码打包器使用 `git ls-files --cached --others --exclude-standard` 与既定白名单，收集尚未暂存的 Go/VERSION 文件、跳过已删除文件及 macOS sidecar，不依赖 `git add`。Java 退休后前一候选 ZIP 为 1157 个文件、10238820 字节，SHA256 为 `ad73813682c06acbdd95de3f413a844bbbe78c6b98658fa05cc57a584901700f`，文件内容与此前完成解压验收的包逐字节一致。完整校验所有 SHA256SUMS、必要源码和契约夹具及敏感文件排除后，在独立解压目录通过前端 `npm run check`、CGO-free Go 编译和 `GOPROXY=off go test -short ./... -count=1`（19 个有测试包，另 2 个无测试包）。后续独立审计发现亲友 H5 音轨权限回归，此候选不再作为最终交付，修复后须重新生成并验收。仅复用本机 npm/Go 模块缓存和 dbmate 工具；未复用原业务数据或密钥。MySQL、真实 Python 往返及长任务在独立专项记录，不将短测跳过计为这些专项通过。

部署包 Python 单元测试本轮 8 项通过，覆盖源码未跟踪/已删除清单、权限、摘要、无私密文件、离线 Compose 禁止构建/拉取，以及导出期间镜像标签变化时拒绝生成 ZIP。原生 compute 版本读取另有 1 项通过。根 `VERSION` 同时用于 Go、原生 compute、Docker/Compose、Bake 与部署包；Dockerfile 未显式收到版本时读取随源码复制的 VERSION，直接 Bake 要求显式传入根版本。

离线打包保留明确指定的 Linux AMD64 配套 Go/Nginx 标签，并在保存前后复查实际镜像 ID；`images.json`、VERSION 与 SHA256SUMS 随包交付。不会把 containerd 的 OCI index ID 直接写入 Compose，以兼容采用 config ID 的经典 Docker 存储。打包过程不重打宿主标签，不收集容器运行文件或卷。

前一候选 AMD64 离线 ZIP 为 68578929 字节，SHA256 为 `1ad22e7e94e16bf6b36340739e9fa56f68c93232d18fdd31a76b70dd97b0c09f`。Go 镜像 ID 为 `sha256:0ca9680d71d150bee16ec05821f7625da16181c36696e95826d1e477fd5e5abe`，配套 Nginx 为 `sha256:ed90ec37ea93892fe629c19ff8566dd2a1368ab2dcc12306d5aec566f9620724`。完整 ZIP 与镜像 tar 的标签/摘要核验通过；实际执行包内 `docker load`，再以 ZIP 自身 Compose、随机项目与 loopback 端口、全新独占卷启动，166 条 HTTP 契约断言全部通过。该共同脚本未覆盖后续发现的亲友 H5 音轨权限回归，因此本包已降为候选，修复后重新导出并验收。测试后仅清理本次创建的资源，并按项目标签确认卷已清空。该次 AMD64 使用 Docker Desktop Rosetta，不能代替原生 AMD64 NAS 真机验收。

## 2026-11-09 Java 工程退休与交付边界

上述业务、数据库、真实 Python、两种架构容器及离线包检查通过后，移除 `services/singverse-java-backend/` 下全部 343 个受版本控制的文件。删除前确认这些文件没有既有工作区修改，再逐文件删除并仅移除空目录；保留下来的 916 个未跟踪或被忽略的本地文件，其 inode、权限、大小和修改时间前后完全一致。没有整树删除旧工程目录，也没有操作既有命名卷或覆盖旧回退镜像。

Java 源码移除后，当时的迁移清单脚本（现已退休）以只读历史基线模式通过：276 个公开操作、276 个 Java 路由、23 个定时入口、556 个 SQL 项；`--write` 明确拒绝覆盖该基线。真实 Go 应用的路由测试再次通过，276 个基线操作全部匹配 281 个注册。Java/Maven 构建目标及后端工作流已删除，默认构建、启动和发布均指向 Go；旧镜像及旧卷的显式回退配置保留。

Java 退休时的源码候选 ZIP 为 1157 个文件、10238820 字节，SHA256 为 `ad73813682c06acbdd95de3f413a844bbbe78c6b98658fa05cc57a584901700f`；退休后再生并逐文件核对，与上文该轮已解压构建和短测试通过的输入一致。该包已被下方 11 月 10 日亲友权限修复后的最终包替代。Make 入口、工作流 YAML、37 组 Compose 配置、部署包单元测试及 `git diff --check` 均通过。

本轮没有升级或切换用户正在运行的实例。NAS ACL、微信扫码、手机/TV 真机、原生 AMD64 硬件、GPU/MPS 模型及长期持续负载仍需单独验收；远程 CI 也未在本机执行。以上历史 Java 或 GPU 记录均不计入 Go 本轮通过结果。

## 2026-11-10 亲友音轨授权回归与最终重验

最终只读审计发现 `/party/audio-roles/songs/:song` 被误挂到设备专用认证组，导致合法亲友 Bearer 返回 403。新增真实 HTTP 用例先在旧实现复现失败，再修复路由装配。对照已冻结的 Java 控制器，音轨 GET/PUT、伴奏读取及按所有者隔离的转码状态/取消/片段使用有效设备或亲友身份；兼容版本及原片/播放资源继续要求设备身份。没有把所有 `/tv/` 路径按同一角色收紧。

`internal/app/party_audio_test.go` 在隔离空库、真实 HTTP 授权与加入流程中，使用不带 Cookie 的亲友客户端验证两个音轨别名的读取、修订、数据库持久化、CAS 409、设备与亲友共用结果，以及撤销后 GET/PUT 401。另断言跨所有者转码返回 404 且数据库状态不变，版本/媒体/播放资源返回 403，伴奏检查继续执行资源有效性验证。生产改动限于应用认证装配和执行层路由分组。

修复后再次运行完整 `scripts/check-go-backend.sh`，格式、vet、staticcheck、govulncheck 和全包 race 均通过，漏洞扫描仍输出 `No vulnerabilities found.`；app 9.389 秒、execution 4.007 秒，其余未变包使用 Go 测试缓存。完整真实 MySQL 隔离检查再次退出 0，包含 app 6.208 秒、auth 41.591 秒、execution 38.768 秒、metadata 56.892 秒，测试容器已清理。

两种架构后端从同一修复源码重新构建，Docker Hub 认证超时后使用相同固定 SHA256 的 Go 基础镜像源完成，没有放宽 TLS。此前 11 月 9 日的候选镜像及 ZIP 检查不覆盖该回归，不作为本次最终交付包；最终包的摘要和解压验收另见下方新记录。

最终 `infra/docker/check-go-container.py` 新增真实 Nginx 下的亲友音轨授权断言，两架构均再次通过：ARM64 原生 166 条共同契约加 104 条交付断言（5.756 秒），AMD64 Rosetta 166 加 105 条（7.253 秒）。断言包含音轨别名/伴奏确实进入业务校验、版本角色拒绝和撤销后的 401，并保留重启持久化、可选 compute health、运行时与 ELF 检查；测试容器及网络标签复核均为空。ARM64 后端最终 ID 为 `sha256:bb1e0b8d49f35a81d69e26eac53853d80e31ec518a306efc455c1da6961032df`，AMD64 为 `sha256:ccdc9f81fd49a0ac44fd5b5dbef0fa9718ab96fc66b8e3438e522de9745b535d`，两架构配套前端未变。AMD64 仍为模拟运行，未作为原生硬件通过。

同日 00:01:50–00:02:08（Asia/Shanghai），当前最终 ARM64 镜像再次通过完整 Nginx / Go / compute 独占 Compose 检查（17.317 秒）：三个静态入口、登录退出、封面校验、AUTO 认证与 callback、GET/POST JSON 404、五种格式标签读取、离线转码、SHA 及原件只读均通过；容器、网络与七个新建卷已全部清理。`scripts/check-go-compute.py` 的真实 Python/FFmpeg `-race` 往返也再次通过，测试体 4.57 秒、包报告 6.325 秒，覆盖配对、回源、HLS/离线范围读取及授权释放。


## 2026-11-10 权限修复后最终交付包

本次最终源码包为 `SingVerse-Go-Business-source-h5-fixed.zip`，1158 个文件、10241471 字节，SHA256 为 `9cbacd7d26b7822d8330bfa707d68581cc1cc0eba6772f5718fa998523159709`。重新核验 ZIP 完整性、全部 SHA256SUMS、契约夹具、新增 `internal/app/party_audio_test.go` 和敏感文件排除后，在全新解压目录实际通过前端 `npm run check`、CGO-free Go 编译和 `GOPROXY=off go test -short ./... -count=1`；19 个有测试包全部通过，另有 2 个无测试包。亲友授权实际 HTTP 回归随 app 包执行，未用此前候选包的通过结果替代。

新源码的真实 scratch COPY 审查再次通过：根兼容规则导出 1228 个文件、13078658 字节，Go 专用规则导出 1148 个文件、11884312 字节；必需构建输入齐全，仍无数据、密钥、缓存、产物或 Java 工程。没有修改已收紧的白名单规则。

本次最终 Linux AMD64 离线包为 `SingVerse-Go-Business-offline-h5-fixed-amd64.zip`，68580979 字节，SHA256 为 `498344d7ea744938bb14730b9a1c3d7a2a9e830ed5bb229d3d37d20c759606c4`。导出及载入后的 Go 镜像 ID 均为 `sha256:ccdc9f81fd49a0ac44fd5b5dbef0fa9718ab96fc66b8e3438e522de9745b535d`，Nginx 为 `sha256:ed90ec37ea93892fe629c19ff8566dd2a1368ab2dcc12306d5aec566f9620724`。所有摘要、镜像 tar 标签及架构核验通过；实际 `docker load` 后，以 ZIP 自身 Compose、随机项目与 loopback 端口、全新独占卷启动，166 条共同 HTTP 契约断言再次全部通过。亲友音轨权限另由上方新增的真实 Nginx 镜像检查及源码 HTTP 用例覆盖，不将共同 166 条断言冒充该项覆盖。

只清理本轮创建的容器、网络和卷，按项目标签确认卷已空；没有操作既有部署。此前候选 ZIP 保留用于追溯，不能替代这两个最终包。该次 AMD64 使用 Docker Desktop Rosetta，原生 AMD64 NAS 及设备/GPU 真机限制保持不变。

## 2026-11-10 本机实例切换供手动测试

用户明确要求替换运行实例后，将本机旧 `singverse-app` 的前端、Java 与旧容器 compute 顺序停止，保留原容器、镜像和全部命名卷。停机后私密备份匹配的 SQLite 文件及主密钥，并保存原容器完整配置和 Mac 节点配置；没有复制旧业务数据库、管理员、会话或加密业务设置，也没有执行 `down -v`。

新 `singverse-go-app` 使用上文最终 ARM64 Go/Nginx 镜像和独立新业务卷，沿用原 HTTP 18080 入口及其原有监听范围。继续连接既有原生 Mac 计算节点，为新 Go 项目配置同一内部部署令牌，没有轮换令牌。原生节点重新加载当前 Python 代码，保留模型目录、4 GiB 音频进程预算、缓存和 VideoToolbox 设置。私密运行记录、启动/回退命令和备份均保存在被 Git 忽略的 `infra/docker/.run/`，不进入源码或部署包。

00:22（Asia/Shanghai）实际入口验证通过：Go 与 Nginx 均 healthy；`/`、`/admin/`、`/party/` 和 10 个 JS/CSS 资源可读；初始 `admin/admin` 登录及退出成功，曲库为空；Mac 节点状态为 AVAILABLE，认证及双向业务回源通过，返回 `media-remote-tags`、HLS/离线及快速音轨能力。未知 GET/POST API 保持 Problem Details 404。共 63 条检查断言，没有新增媒体来源、队列或业务配置。旧三个容器保持停止、原 ID/镜像不变，无关 MySQL 保持健康。

本节验证的是实际本机切换与连通性；尚未将用户后续手动测试、真实歌曲播放、GPU/MPS 执行或微信/TV/NAS 真机记为通过。先前隔离部署的清理记录保持其原范围，当前供用户测试的新 Go 实例持续运行。

## 2026-11-10 database/sql + sqlx 改造与本机更新

连接及事务统一为 `*sqlx.DB` / `*sqlx.Tx`，继续通过 `store.DBTX` 在同一事务内协作；`database/sql` 仍提供驱动、连接池及基础类型。固定 `sqlx v1.4.0`，原有 SQLite 单连接、MySQL 最多五连接、部署锁和 dbmate 流程保留。本轮未修改数据库结构或已发布迁移；业务 SQL 仍在所属模块的嵌入文件中，仅为结构体读取补充结果列别名。

管理员、AI 配置、曲库及反馈使用独立数据库行模型和显式 `db` 标签，接入 `GetContext` / `SelectContext` / `StructScan`；全库或无界结果继续逐行处理。通用投影由 sqlx `MapScan` 统一读取，保留 MySQL 文本、二进制、NULL 和非空切片语义。原有 `@{name}` 标记交由 sqlx 命名绑定，增加字面冒号、URL、Unicode、重复参数、缺参脱敏、事务回滚和单可用连接测试。

| 检查 | 本轮实际结果 |
|---|---|
| 完整 Go 检查 | `env GOPROXY=off sh scripts/check-go-backend.sh` 通过格式、vet、staticcheck、govulncheck 及全包 `go test -race ./...`；漏洞检查输出 `No vulnerabilities found.`。初次沙箱因无法连接漏洞数据库中止，允许联网后完整重跑通过 |
| 真实 MySQL | `env GOPROXY=off python3 infra/docker/check-go-mysql.py` 在新建隔离 MySQL 8.4.8 中通过；包含新增 AI 配置、反馈并发修订/解决状态及 sqlx 参数/类型/事务用例，使用 race 检测。测试容器随后清理，没有操作已有业务库 |
| 真实 Python | `env GOPROXY=off python3 scripts/check-go-compute.py` 通过，实际往返测试体 4.44 秒、包报告 6.440 秒；包含配对与回源、本地/WebDAV 标签、音轨探测、HLS/离线导出、范围读取、校验与授权释放 |
| 本机恢复 | 最终 macOS 二进制通过 `python3 scripts/check-go-recovery.py` 的 74 条真实 PTY 断言，覆盖回显关闭、占用锁、非 TTY 拒绝、会话撤销及恢复后账号/MFA 状态；Linux 最终镜像另通过 `infra/docker/check-auth-docker.py` 的真实 TTY 恢复、改密/MFA 和重建持久化检查 |
| 平台构建 | 最终源码以根 `VERSION` 构建 macOS 二进制及 `CGO_ENABLED=0` 的 Linux ARM64、AMD64 二进制。ARM64 实际容器运行通过；本轮 AMD64 仅编译，没有把此前模拟运行结果计为本次运行通过 |
| ARM64 交付镜像 | `infra/docker/check-go-container.py` 使用本轮后端及既有配套前端，在原生 ARM64 Docker VM 中通过 166 条共同 HTTP 契约及 104 条交付断言，包含认证媒体边界、重启持久化、可选 compute 健康及无 JVM/Node/编译器运行时检查 |

TTY 首测暴露原有顺序竞态：显示新密码提示后，`term.ReadPassword` 才关闭回显，快速输入可能出现在终端。现于提示前关闭 `ECHO` / `ECHONL`，结束后恢复原终端状态；保留密码不得出现在输出中的断言，并增加提示出现时直接核对终端标志的检查。该失败已修复后重验，没有删除断言或操作本机测试实例的管理员密码。

完整 `infra/docker/build/go.Dockerfile` 多阶段 ARM64 构建先后在固定摘要的镜像源及默认 Docker Hub 因 Go 基础镜像元数据请求超时中止，均发生于源码编译前，本轮不记录为完整 Docker 源码构建通过。测试镜像使用同一固定运行时基础及标准运行阶段，复制本机由最终源码交叉编译的二进制与从既有已验收镜像提取、SHA256 再校验的固定 dbmate；没有修改受控 Dockerfile、依赖锁定、TLS 或全局 Go 配置。

测试镜像标签为 `singverse-go-backend:sqlx-20261110-arm64`，镜像 ID 为 `sha256:45506e404ba886a3a8c87f234baca34cdb97057df1e86d2a0ea9e256dc19da2e`，二进制 SHA256 为 `0b7f42f0b87dc6a191a74e7d59734a17cdfa4b8190c1865409e428eec531a14d`。容器及认证检查使用各自独占测试项目，结束后已清理；上文源码与离线 ZIP 为本次 sqlx 改造前的产物，本轮没有重新打包。

隔离验证通过后，只重建本机 `singverse-go-app` 的 Go 服务，并重载 Nginx 上游解析。当前五个挂载的卷名、读写模式和后端环境配置与更新前一致，前端容器/镜像及端口不变；旧 Java 项目继续停止，原生 Mac compute 本轮未修改。对实际入口只执行只读检查，没有使用初始账号登录、导入数据或重置密码。健康 UP、三个页面、10 个 JS/CSS、匿名会话与未知 API 的 401 Problem Details、运行二进制摘要和部署保护共 58 条断言通过。

第一次更新检查曾将未登录未知 API 误判为 404，自动恢复旧 Go 镜像；核对原实例同样返回 401 后修正私密检查脚本，再次更新通过，没有更改生产认证行为。当前手动测试实例继续运行，沿用 `http://127.0.0.1:18080/admin/` 入口。私密启动、回退、镜像构建及部署记录位于被忽略的 `infra/docker/.run/`。本轮未将用户后续测试、NAS/微信/TV/手机真机、GPU/MPS、长期负载或远程 CI 记录为通过。

## 2026-11-10 Go 核心流程可读性整理

本轮以现有 HTTP、状态及事务契约为约束，集中整理四个核心包。`app` 分开生命周期、按顺序创建/关闭服务、路由认证边界、跨模块适配和观测；`party` 分开设备/邀请/会话/播放资源处理器，并将队列幂等、权限分派、主持操作、播放指令及回报拆为命名步骤；`background` 分开投影编排、各类型细节、计数/时间与分页明细持久化；`execution` 将音轨、批次、模型、转码和缓存路由归入各自 HTTP 文件，模型上传、离线查询及缓存任务等待归回应用逻辑。

| 入口函数（包含声明及空行） | 整理前行数 | 整理后行数 |
|---|---:|---:|
| `app.New` | 209 | 24 |
| 后台任务 `refresh` | 324 | 54 |
| 聚会 `change` → `changeQueue` | 248 | 54 |
| 媒体执行 `Register` | 266 | 7 |
| 聚会 `Register` | 220 | 8 |

业务实现按职责保留在各个函数中。聚会身份认证和操作继续复用同一事务执行器；幂等回执先于修订校验，预取仍于成功提交后执行。应用保持原服务创建、回调登记及逆序关闭的顺序，保留 Gin 注册时的管理员、TV/亲友 Bearer 与 compute grant 边界。后台任务保留过期判定、历史时间未知语义、内容指纹、子任务及错误脱敏。源码 SQL、dbmate 迁移、依赖锁定、前端及协议文件本轮未改动；阅读入口见 [Go 工程说明](../services/singverse-go-backend/README.md#代码约定与验证)。

新增 `party/queue_http_test.go` 从实际 HTTP 验证点歌、自己的/他人的撤回、修订冲突、置顶/上下移动、锁单、授权主持、电视确认开唱、暂停/恢复/重播、撤销与结束。新增 `background/projection_test.go` 在 SQLite 与真实 MySQL 验证失败和历史跳过数量、音轨预览进度、清理统计/错误脱敏、重复核对的时间稳定性及旧任务保留策略。SQLite 和队列测试先在原实现通过，再执行重构后的回归；既有断言保留。

| 检查 | 本轮实际结果 |
|---|---|
| 完整 Go 检查 | `env GOPROXY=off sh scripts/check-go-backend.sh` 通过格式、vet、staticcheck、govulncheck 和全包 race，漏洞检查输出 `No vulnerabilities found.`。app 11.655 秒、background 6.797 秒、execution 7.766 秒、party 20.950 秒；未变包可复用有效缓存。真实 HTTP 认证边界及全部基线路由覆盖随 app 用例执行 |
| 真实 MySQL | `env GOPROXY=off python3 infra/docker/check-go-mysql.py` 在全新独占 MySQL 8.4.8 中最终通过。新增投影行为用例实际执行，background 10.197 秒、app 6.212 秒、execution 18.155 秒；其余已有 MySQL 业务用例同轮通过，测试容器随后清理 |
| 真实 Python | `env GOPROXY=off python3 scripts/check-go-compute.py` 的真实 Python/FFmpeg 往返通过，测试体 3.72 秒、包报告 5.700 秒；覆盖发现、配对、认证回源、标签/音轨、HLS、导出/本地重播、离线转换、范围读取及只读原件 |
| 源码与脚本 | MySQL 检查脚本语法及 `git diff --check` 通过；对照本轮修改前的源码快照核对文件范围，SQL 与依赖锁文件没有差异，已有未提交工作保留 |

首轮 MySQL 全量有几组用例在创建随机数据库前报 `unexpected EOF` / `invalid connection`，稍后启动的用例正常。读取本机固定 MySQL 镜像入口确认，初始化临时服务通过 `--skip-networking` 仅开放 socket；原脚本的 socket `mysqladmin ping` 会提前放行。现使用带认证的 TCP `SELECT 1` 并核对结果，再开始测试；在另一个全新容器中完整重跑通过，没有重试或跳过业务断言，也没有放宽生产数据库连接策略。

本轮没有重建或重启用户正在手动测试的 Go/Nginx 实例，也没有操作其数据库、账号、配置或卷；Python 往返使用隔离计算子进程。未重复 Docker 镜像、TTY 恢复、跨架构或 NAS/微信/TV/手机/GPU 真机验收，先前各项结果仍属于各自记录，不算本轮通过。
