# SingVerse 前端与反向代理契约

统一前端源码在services/singverse-frontend，Go后端在services/singverse-go-backend。亲友侧提供聚会扫码、点歌、队列及授权遥控；管理侧包含授权、曲库、媒体、设备和系统设置。两者同属一个前端工程与一份dist。

## 路由与产物

| 内容 | Go直接托管（含Docker） | 外部反向代理要求 | 静态产物 |
|---|---|---|---|
| 管理页面 | `/` 与 `/admin/` | `/`，同时兼容 `/admin/` | `dist/admin/index.html` |
| 点歌H5 | `/party/` | `/party/` | `dist/party/index.html` |
| 构建JS/CSS | `/assets/*` | `/assets/*` | `dist/assets/*` |
| 项目 Logo 与浏览器图标 | `/assets/brand/*` | 相同路径 | `dist/assets/brand/*`，来自前端 `public/assets/brand/` |
| 亲友欢迎样式 | `/party/assets/welcome.css` | 相同路径 | `dist/party/assets/welcome.css` |
| 健康检查 | `/api/health` | 同路径转发后端 | 兼容整体健康JSON，不属于业务API |
| 账号/MFA及后续业务 | `/api/v1/*` | 同路径转发后端 | 按独立业务OpenAPI实现 |

`/admin`、`/party`补尾斜杠；目录首页及显式index均可访问。页面返回200和text/html，HEAD无响应体，JS/CSS类型正确；缺失资源和不存在的API返回真实404，不回退成管理HTML。

前端使用相对构建资源URL。管理hash路由依据浏览器实际访问地址确定基址，可在 `/admin/#/overview` 或根路径 `/#/overview` 打开；亲友链接始终指向 `/party/`。业务路径、静态资源和管理入口不因Go或Go实现语言变化而变化。

## API前缀

前端API适配层只允许同源 `/api`、`/api/*` 请求；当前调用 `/api/health` 和 `/api/v1` 账号/MFA接口，发送Accept: application/json。Go 提供兼容健康响应 `{"status":"UP"}`，数据库不可用时 DOWN/503，不公开数据库详情。前端不直连Python或数据库。

外部代理匹配整个 `/api` 命名空间并保留URI和查询参数，不剥离API前缀；`/api/v1/songs?page=0`不会被剥离为 `/v1/songs?page=0`。业务API继续使用 `/api/v1`；运维健康不套业务分页或RFC9457错误结构。

健康适配仅接受字符串UP、DOWN、OUT_OF_SERVICE、UNKNOWN并忽略额外字段；HTTP失败、HTML、非法JSON、超时或断网均显示失败，仅UP显示正常。

## 开发、交付与验证

Vite本地提供 `/admin/` 和 `/party/` 两入口，仅将 `/api` 代理到Go，默认后端为127.0.0.1:8080。原生二进制由 Go 嵌入托管完整 dist，不需要生产Node进程。Docker 业务容器同样由 Go 直接托管，不再提供独立 Nginx；HTTPS 使用外部反向代理。

`make frontend-check`验证类型与构建，前端模块 `npm test` 验证API前缀、请求行为和管理挂载路径；`make web-check`验证Go实际HTTP；`make backend-check`验证Python上下线隔离。外部代理部署前需验证根页、party页、引用资源、健康、未知API及缺失JS/CSS，不能仅靠静态构建成功宣告代理可用。

Go 复用同一 dist 与协议，静态与真实 HTTP 检查见 `make web-check`、`make docker-check`。当前根路径或/admin挂载支持不代表任意路径前缀、WebSocket、NAS部署或微信真机验收完成。

请求大小由 Go 各业务接口自行校验。外部反向代理需允许相应 JSON、原始附件和 multipart 请求到达业务校验层，不能沿用旧 Nginx 的全局小请求限制。正式元数据 JSON 为 16 MiB、原始附件及歌曲封面文件为 32 MiB；multipart 还需预留封装开销。图片等具体限制以对应业务接口契约为准。

HLS 播放的 MSE MediaSource 和 hls.js worker 需要 `media-src 'self' blob:` 与 `worker-src 'self' blob:`；网络仍仅 `connect-src 'self'`。外部代理对转码清单/分片及兼容版本关闭缓冲和磁盘缓存，并保留原始媒体 Range 行为。
