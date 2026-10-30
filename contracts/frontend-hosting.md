# SingVerse 前端与反向代理契约

统一前端源码在services/singverse-frontend，Java后端在services/singverse-java-backend。当前亲友侧仅欢迎页，管理侧包含概览、健康查询、单管理员登录和MFA设置；没有业务点歌。两者同属一个前端工程与一份dist。

## 路由与产物

| 内容 | Java直接托管 | Nginx对外托管 | 静态产物 |
|---|---|---|---|
| 管理页面 | `/admin/`，根路径重定向到此 | `/`，同时兼容 `/admin/` | `dist/admin/index.html` |
| 点歌H5 | `/party/` | `/party/` | `dist/party/index.html` |
| 构建JS/CSS | `/assets/*` | `/assets/*` | `dist/assets/*` |
| 亲友欢迎样式 | `/party/assets/welcome.css` | 相同路径 | `dist/party/assets/welcome.css` |
| 健康检查 | `/api/health` | 同路径转发后端 | Actuator原生JSON，不属于业务API |
| 账号/MFA及后续业务 | `/api/v1/*` | 同路径转发后端 | 按独立业务OpenAPI实现 |

`/admin`、`/party`补尾斜杠；目录首页及显式index均可访问。页面返回200和text/html，HEAD无响应体，JS/CSS类型正确；缺失资源和不存在的API返回真实404，不回退成管理HTML。

前端使用相对构建资源URL。管理hash路由依据浏览器实际访问地址确定基址，可在 `/admin/#/overview` 或根路径 `/#/overview` 打开；亲友链接始终指向 `/party/`。业务路径、静态资源和管理入口不因Java或未来Go实现语言变化而变化。

## API前缀

前端API适配层只允许同源 `/api`、`/api/*` 请求；当前调用 `/api/health` 和 `/api/v1` 账号/MFA接口，发送Accept: application/json。Java通过management.endpoints.web.base-path=/api提供原生Actuator健康响应；健康时status为UP，不公开数据库详情。前端不直连Python或数据库。

Nginx匹配整个 `/api` 命名空间并保留URI和查询参数，proxy_pass不附加替换URI；`/api/v1/songs?page=0`不会被剥离为 `/v1/songs?page=0`。业务API继续使用 `/api/v1`；运维健康不套业务分页或RFC9457错误结构。

健康适配仅接受字符串UP、DOWN、OUT_OF_SERVICE、UNKNOWN并忽略额外字段；HTTP失败、HTML、非法JSON、超时或断网均显示失败，仅UP显示正常。

## 开发、交付与验证

Vite本地提供 `/admin/` 和 `/party/` 两入口，仅将 `/api` 代理到Java，默认后端为127.0.0.1:8080。运行JAR由Java托管完整dist，不需要生产Node进程。Nginx配置位于[Java后端nginx目录](../services/singverse-java-backend/nginx/README.md)，对外根页读取admin目录，party和assets读取完整dist中的对应目录。

`make frontend-check`验证API前缀、请求行为、管理挂载路径、类型与构建；`make web-check`验证Java实际HTTP；`make backend-check`验证Python上下线隔离。Nginx部署前需验证根页、party页、引用资源、健康、未知API及缺失JS/CSS，不能仅靠静态构建成功宣告代理可用。

未来Go复用同一dist与协议，需在真实Go服务上重复验收。当前根路径或/admin挂载支持不代表任意路径前缀、WebSocket、NAS部署或微信真机验收完成。

分类封面上传 `/api/v1/classification/tags/{id}/cover` 与已有歌曲封面上传一起使用Nginx专用3MiB请求上限，其余API仍保留16KiB默认上限。Java继续限制单文件2,000,000字节及图片格式/尺寸，客户端不能依靠代理上限绕过校验。`make docker-check` 用大于16KiB的无效图片验证代理实际转发到Java且不写数据。

HLS 播放的 MSE MediaSource 和 hls.js worker 需要 `media-src 'self' blob:` 与 `worker-src 'self' blob:`；网络仍仅 `connect-src 'self'`。转码清单/分片及兼容版本代理关闭缓冲和磁盘缓存，并保留原始媒体 Range 行为。
