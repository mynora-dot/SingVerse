# SingVerse Nginx 配置

`nginx.conf`是完整Nginx配置，用于主机部署或Nginx容器打包。当前[Dockerfile](../Dockerfile)和[应用Compose](../../../compose.app.yaml)已使用此配置，运行入口为仓库根目录 `make docker-up`；NAS实机尚未验收。

## 路径约定

| 对外请求 | 行为 |
|---|---|
| `/` | 读取完整前端产物中 `admin/index.html`，管理端对外使用根路径 |
| `/assets/*` | 前端构建资源，对应 `dist/assets/*`，不随管理入口映射改变 |
| `/admin/` | 兼容直接管理入口，同一份管理页面 |
| `/party/` | 点歌H5入口，对应 `dist/party/index.html`，当前仅欢迎页 |
| `/party/assets/*` | 点歌H5静态资源 |
| `/api`、`/api/*` | 原路径及查询参数转发到Java后端，包括 `/api/health` 与后续 `/api/v1/*` |

API代理不剥离 `/api`。例如 `/api/v1/songs?page=0` 仍以相同路径交给后端；当前未实现的接口保持真实404，不回退成HTML。前端请求集中在API适配层，并校验必须带 `/api` 前缀。健康端点保留Actuator原生响应，不套业务错误或分页结构。

管理端构建使用相对资源地址，hash路由按浏览器实际访问位置确定基址，因此同一产物可在 `/` 或 `/admin/` 打开。页面中的亲友链接固定指向 `/party/`。

## Docker打包

联合Dockerfile在Node构建阶段生成前端产物，并把**整个** `services/singverse-frontend/dist` 放到Nginx容器的 `/usr/share/nginx/html`，保持admin与party两层目录；将本配置放到 `/etc/nginx/nginx.conf`。Dockerfile以仓库根作为构建上下文，复制位置对应如下（dist来自构建阶段）：

```dockerfile
COPY services/singverse-java-backend/nginx/nginx.conf /etc/nginx/nginx.conf
COPY services/singverse-frontend/dist/ /usr/share/nginx/html/
```

配置默认通过Docker内部DNS访问 `singverse-java-backend:8080`。应用Compose使用同名后端服务，两个容器位于同一内部网络。完整命令、默认18080端口及持久化见[Docker说明](../../../docs/docker.md)。

## 主机部署与检查

主机部署时，将upstream的后端地址调整为实际内网地址，将静态root对应到完整dist中的admin目录；party及admin兼容入口的root对应到完整dist目录。`mime.types`路径按实际Nginx安装位置配置。它是完整配置，合入已有Nginx时需按已有http/server结构整理，不能直接当server片段include。

启动前使用部署环境中的 `nginx -t -c <配置绝对路径>`。实际联调检查管理根页、`/party/`、两端资源、`/api/health`、未知API和缺失JS/CSS；未知API不得返回管理HTML。反向代理映射依据[Nginx proxy_pass文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)，其中当前配置没有URI替换部分。

当前没有业务WebSocket端点，未预置WebSocket升级转发。首项真实WebSocket业务落地时再同步其 `/api` 路径、代理与连通测试。

HTTP/HTTPS代理scheme通过proxy-scheme.conf显式区分；HTTPS外层部署使用根compose.https.yaml覆盖，不信任请求自带的scheme。认证与TLS入口说明见[认证文档](../../../docs/authentication.md)。


管理端／手机原流 `/api/v1/media-songs/{id}/video`、TV 原流 `/api/v1/tv/media/{id}`、两端生成伴奏、HLS 清单／分片、离线版本及内部转码／音轨回源共用媒体流规则，保留 Range/HEAD 并关闭 proxy_buffering、proxy_cache，读取空闲超时 60 秒；鉴权由 Java 执行，不暴露 WebDAV 凭据。升级播放链路时须同步更新并重载 Nginx 配置。

`make nginx-media-check` 使用本机已有 `nginx:stable-alpine` 与 `node:22-bookworm-slim` 镜像验证实际代理；不拉取镜像，不启动业务应用，不挂载数据库、媒体或凭据。测试创建独立网络及仅绑定回环的随机端口，检查十条媒体路径的 GET/HEAD/Range，并在上游剩余正文被门禁阻塞时断言首段已到达客户端，结束后删除测试容器和网络。镜像名称可用 `python3 scripts/check-nginx-media.py --nginx-image <本地镜像> --backend-image <本地镜像>` 覆盖。

电视封面上传 `/api/v1/admin/tv-settings/banners` 复用图片上传专用 3m 请求体限制（应用校验单张2MB），查询参数 `revision` 不影响匹配；其他普通接口继续使用16k。升级电视设置上传功能时须同步更新并重新加载 Nginx 配置，不能只更新 Java 或前端。多选上传由前端逐张提交，不增加单次请求体上限。
