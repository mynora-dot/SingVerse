# SingVerse Python 计算服务

Python 3.12+ / FastAPI。所有 FFmpeg/ffprobe 执行都由本工程处理，Java 通过内部 HTTP 编排、校验和入库，前端不直连计算服务；Python不访问业务库、不管理业务队列。当前支持本地音视频标签读取，以及实时 HLS/离线兼容版本、六类硬件编码适配。已增加按素材试跑的硬解、零拷贝、GPU 色调映射与 DV P5/P8 转 SDR；模型任务尚未实现；详见[转码说明](../../docs/transcoding.md)。

## 运行与验证

首次 `make compute-setup PYTHON=python3.12`，依赖使用本工程 `.venv` 与 `requirements.lock`。本地运行 `make compute-run` 默认只监听127.0.0.1:8090；需安装FFmpeg以提供标签能力。`make compute-check`执行随机端口HTTP、真实生成音视频、超时/输出限制测试；`make backend-check`执行Java/Python整体及真实双进程集成测试，使用隔离数据。

`python -m singverse_compute --host 127.0.0.1 --port 8090`启动。配置：

- `COMPUTE_MEDIA_ROOTS`：允许读取的绝对根目录，多个目录用系统路径分隔符（Linux/macOS为冒号）。默认空，不允许读取媒体。Java与计算服务必须挂载同一份媒体，路径不同可在管理员页面配置根目录映射。
- `COMPUTE_AUTH_TOKEN_FILE`：内部Bearer凭据文件。CLI首次启动以0600生成随机凭据；Java仅只读挂载该文件，禁止提交或输出内容。没有有效凭据、根目录或ffprobe时不声明标签能力。
- Java用 `COMPUTE_NODE_URL` 与 `COMPUTE_AUTH_TOKEN_FILE` 连接。Java不启动Python或FFmpeg子进程，节点离线/失败回退文件名，不影响主服务健康。

应用Compose默认启动独立 `singverse-compute` 容器，不发布端口；媒体卷 `/media` 只读，内部凭据使用独立命名卷。Java镜像不含Python/FFmpeg。自定义NAS挂载应同时给Java和compute添加同一份媒体的只读挂载，并在compute根目录白名单中列入。跨主机部署需要额外TLS与受限网络，不能直接暴露计算端口。

## 工程结构与依赖

```text
singverse_compute/
├── __main__.py                  # CLI，只解析启动参数
├── bootstrap.py                 # 应用工厂，唯一依赖组装入口
├── app.py                       # ASGI导入兼容入口，不承载业务
├── core/                        # 不依赖HTTP的基础约定
│   ├── config.py                # 不可变部署配置与路径验证
│   ├── errors.py                # 业务/基础设施错误语义
│   └── logging.py               # JSON日志、安全字段白名单
├── domain/
│   └── media_tags.py            # 不可变媒体标签值对象
├── application/
│   └── media_tags.py            # 用例、端口协议、并发/失败释放
├── infrastructure/
│   ├── filesystem.py            # 媒体根目录授权与规范路径
│   ├── security.py              # 内部凭据初始化、读取、鉴权
│   └── media/ffprobe.py         # FFprobe进程、预算与输出归一化
└── api/
    ├── schemas.py               # HTTP输入/输出DTO
    ├── dependencies.py          # FastAPI依赖注入和Bearer声明
    ├── middleware.py            # 请求追踪、鉴权前置、请求体上限
    ├── errors.py                # Problem Details统一映射
    └── routers/
        ├── health.py           # 健康和真实能力
        └── media.py            # 媒体任务入口，仅调用用例

tests/
├── unit/                       # 适配器、路径/凭据/日志/用例边界
└── integration/                # 真实HTTP、错误契约与认证
```

依赖方向：`api → application → domain/core`；`infrastructure`实现应用端口；`bootstrap`负责组装具体适配器。application/domain不导入FastAPI或subprocess，不访问业务数据库。不得将FFmpeg调用放回Java或路由函数，不建立泛化BaseService/Repository空壳。

每个应用实例拥有独立配置、凭据适配器和任务并发门，不使用模块全局Semaphore或动态全局环境作为请求状态。CLI使用工厂模式启动，`app.py`仅保留既有ASGI导入兼容。日志只包含请求ID、方法、路径、状态和耗时，不记录请求体、Authorization或内部凭据；响应带`X-Request-ID`，错误统一为Problem Details，参数错误不回显输入。

扩展与详细职责见[计算服务架构](../../docs/compute-architecture.md)。

## 内部接口

| 路径 | 行为 |
|---|---|
| GET /health | 进程健康，不证明媒体可处理 |
| GET /internal/v1/capabilities | 仅在工具、根目录和凭据齐备时声明media-tags，否则空列表 |
| POST /internal/v1/media/tags | Bearer鉴权；请求 `{path: "/media/歌曲.mkv"}`，返回title/artists/album/releaseDate。路径必须在白名单内 |
| GET /openapi.json | 受版本管理的内部契约；交互文档关闭 |

输入超限413、未认证401、目录越界403、并发繁忙429、能力不可用503；所有外部调用均由Java发起。标签读取仍是同步任务；转码执行和 Java 离线队列见[转码说明](../../docs/transcoding.md)。节点单进程，标签读取最多2个并发，不阻塞健康接口。

## 标签读取边界

`infrastructure/media/ffprobe.py`是计算服务内的唯一FFprobe适配入口。支持MP3/FLAC/M4A/MP4/OGG/WAV/MKV等常见容器，读取非空歌名、歌手、专辑、发行日期；容器值优先于流值，分号分隔歌手转为数组。无标签/损坏/工具缺失/超时返回空字段，Java保留文件名回退。本次不提取封面、歌词、时长，不转码、不写原文件，WebDAV不下载内容。

ffprobe仅允许本地file协议与明确媒体demuxer，限制探测预算、输出256KiB与3秒超时；Java有5秒HTTP超时及64KiB响应上限，认证请求体最大8KiB。相同文件扫描受Java既有2000首/20000条目/30秒范围限制，超出保留原索引。Python不接收任意命令或URL。

更新内部契约后重新生成并执行测试：

```sh
cd services/singverse-compute
.venv/bin/python -c 'import json; from pathlib import Path; from singverse_compute.app import app; Path("../../contracts/openapi/compute-internal.openapi.json").write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2)+"\n",encoding="utf-8")'
```

参考 [ffprobe官方文档](https://ffmpeg.org/ffprobe.html)。本机容器验证不能代替NAS/微信/TV真机或其他架构验收。

## 管理端配置与独立部署

“系统设置 → 性能节点配置”支持 AUTO/MANUAL/DISABLED。同套 `compose.app.yaml` 通过已知服务名和共享凭据自动识别，无需填写，也不扫描局域网。`GET /internal/v1/status` 使用 Bearer 认证返回身份、API 版本和实际能力；健康页可用不代表认证或媒体能力可用。

独立部署示例（在目标设备的仓库根执行，媒体原件只读）：

```sh
COMPUTE_MEDIA_PATH=/absolute/path/to/media docker compose -f compose.compute.yaml up -d --build
```

默认仅发布 `127.0.0.1:18090`。跨主机部署时显式设置 `COMPUTE_HTTP_BIND` 为该设备受限内网地址，限制入站访问；公网使用可信 TLS 反向代理，保留认证与证书验证。主应用 Compose 的计算端口保持不公开。`compose.compute.yaml` 使用独立项目与凭据卷，不启动业务数据库。

节点初次启动生成认证 token，路径为 `/run/singverse-compute/token`。有权限的部署管理员可在目标设备通过以下命令读取，并填入管理页面的“认证凭据”；该值是敏感信息，不粘贴到日志或工单：

```sh
docker compose -f compose.compute.yaml exec -T singverse-compute cat /run/singverse-compute/token
```

在管理页面选择独立部署，填写 **Java 能访问到的** 地址（例如受限内网 `http://192.168.1.20:18090`）。两端挂载必须是同一份媒体；本机 `/media`、计算端 `/media` 相同则无需映射；若 Java 使用 `/mnt/music`，添加 `/mnt/music → /media`。映射按最长本机路径前缀匹配；未匹配路径保持原样，计算端仍执行真实路径白名单检查。配置不会上传媒体，不能令计算节点读取未挂载文件。

测试连接只检查身份、认证和能力，不试读媒体；保存后新扫描才使用配置，进行中的扫描固定原连接。节点不可用时保留文件名回退。可用能力包括 `media-tags`；转码目录、工具与认证就绪时另声明 `media-probe`、`transcode-hls`、`transcode-offline`。没有多节点调度或负载均衡。停止用 `docker compose -f compose.compute.yaml down`，保留命名卷。

## 后台硬件加速配置

管理员可在“系统设置 → 性能节点配置 → 转码与硬件加速”测试及保存后端，设置持久化在转码缓存根目录 `hardware-settings.json`。后台选择优先于 `COMPUTE_HARDWARE_BACKEND`，选择“遵循部署配置”恢复部署默认。仅影响新任务，不自动修改容器设备和驱动；接口、生命周期及部署前提见[转码说明](../../docs/transcoding.md)。

完整硬件图通过 `infrastructure/transcoding/pipelines.py` 的固定模板选择，执行前用实际素材试跑。任务返回 `pipeline` 各阶段和观测 RSS；能力页的硬解/零拷贝/GPU 色调映射只来源于已通过的素材试跑。`COMPUTE_PROCESS_MEMORY_BYTES` 默认 1 GiB，以观测 RSS 检查并终止超限子进程；Linux 容器额外默认 2 GiB 内存/2 CPU 硬限制，可按 NAS 资源调整。
