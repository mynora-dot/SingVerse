# 单管理员账号与二次验证

当前只有一个管理员，初始登录账号为admin，可修改登录账号，没有注册、成员或角色管理。首次空库初始化admin/admin，此后重启、迁移和容器重建均不覆盖密码。默认密码仅持续提示修改，不强制；保留默认密码且未开启MFA的实例不应开放公网。

## 使用

打开管理页先登录。进入「系统设置 → 账号与安全」修改登录账号、密码或配置MFA。新密码15～128个Unicode字符，允许空格和密码管理器，不强制字符组合。修改密码需当前密码；MFA已开启时还需动态码或恢复码。

MFA使用RFC6238 TOTP：认证器扫描本地生成的二维码（或输入手动密钥），再用6位动态码确认。5分钟内未确认的绑定不生效；重新绑定期间旧MFA继续生效。每次绑定确认或重新生成提供10枚恢复码，每枚仅能使用一次，明文只在本次响应和页面显示，可下载离线保存。取消绑定不关闭原MFA。

已开启MFA的账号先验证密码，再验证动态码/恢复码；密码通过不授予管理权限。绑定确认、修改登录账号、改密、关闭MFA和重新生成恢复码均撤销所有会话，需重新登录。恢复码不能单独代替账号密码。亲友/party/仍是公开欢迎页，与管理员身份独立。

## 会话、接口与存储

- 正式Cookie为singverse_session，受限MFA阶段为singverse_challenge，匿名CSRF上下文为singverse_csrf；均HttpOnly、SameSite=Lax、Path=/、无Domain。HTTPS模式加Secure。浏览器不持久化令牌到localStorage。
- 会话空闲30分钟、绝对8小时失效，MFA阶段与绑定有效5分钟，最多5次失败。账号与来源地址共同限流，5次失败后指数冷却，最大15分钟，429携带Retry-After；成功完成认证清除对应限流。安全事件仅保存事件类型与时间，保留90天。
- 每次写操作先GET /api/v1/auth/csrf，将返回token置于X-CSRF-Token头。登录、MFA完成等Cookie切换后重新获取。跨来源请求被拒绝，所有API响应禁止缓存。接口见[OpenAPI](../contracts/openapi/singverse.openapi.json)。
- 密码是标准Argon2id PHC字符串：v19、m19456 KiB、t2、p1、16字节盐、32字节输出，无Spring前缀。会话令牌是32字节随机值的无填充Base64URL，数据库仅保存SHA256十六进制摘要；恢复码为16随机字节的小写十六进制，仅存摘要。
- TOTP：Base32密钥、SHA1、6位、30秒，允许前后一个时间步；原子更新last_totp_step拒绝重放。AES-256-GCM密文格式v1:Base64(nonce12):Base64(ciphertext+tag16)，AAD为UTF8的singverse:totp:v1。加密主密钥文件是Base64编码32字节随机值，权限0600。
- 数据库使用普通SQL表，不存运行时序列化对象；时间字段为UTC Unix毫秒，API时间为ISO8601 UTC。Go 保留原 PHC、加密格式、Cookie 与 HTTP 契约；迁移执行者和业务写入者始终各一个。

## 数据库迁移与运行

[database/migrations](../database/migrations)分别维护SQLite/MySQL的`202611070001_initial_schema.sql`，从空库直接创建完整业务结构，当前不提供旧版数据库升级路径。dbmate2.37.0官方二进制按SHA256固定，执行make dbmate-setup安装到被忽略的.tools；Docker构建自动安装，Go启动在接收请求之前执行同一dbmate migrate。迁移超时或失败即停止，普通启动不执行down。后续结构变更另加版本；空库及回退重建检查使用make db-fresh-check，详见[数据库策略](database-strategy.md)。

主机 Go 启动前先 `make go-backend-build dbmate-setup`，再按后端 README 运行原生二进制；从仓库或模块工作目录运行。默认SQLite旁创建auth/master.key，可通过SINGVERSE_AUTH_KEY_FILE设置绝对路径；MySQL主机默认位于仓库data/auth。Docker使用singverse-data和独立singverse-secrets卷，密钥/secrets/master.key。数据库已有MFA密文但密钥缺失/错误时明确启动失败，不生成替代密钥。

备份必须同时覆盖数据库与匹配的主密钥。先停止后端再备份卷/文件，或使用数据库一致性备份工具；不能直接复制正在写入的SQLite文件作为完整备份。恢复后核对权限、健康与登录，不执行down -v。

## 本机管理员恢复

遗忘密码且无可用认证器/恢复码时，仅能访问部署主机恢复。停止业务主服务，执行交互命令：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml stop singverse-go-backend
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml run --rm --no-deps singverse-go-backend --reset-admin
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml up -d --wait
```

命令要求真实TTY、输入RESET确认并两次输入新密码，不接受命令参数或环境变量传密码。恢复清除MFA、待绑定状态、恢复码、限流和所有会话，不清除其他业务数据。主机原生二进制使用同一配置执行 `bin/singverse --reset-admin`。进程锁阻止同一部署在服务仍运行时恢复；跨主机运行前需保证其他写入者全部停止。恢复命令无网络接口，不提供默认密码后门。

## HTTPS公网入口

默认仍为127.0.0.1:18080的HTTP本机测试。公网需要已有HTTPS反向代理终止TLS、有效证书及受控网络；域名与证书不由本次功能自动部署。

1. 外部代理将HTTPS站点转到本机127.0.0.1:18080，并保留原始Host（含非标准端口）。应用Nginx端口不得直接开放公网。
2. 设置SINGVERSE_PUBLIC_ORIGIN为准确外部来源，例如https://ktv.example.com，不带末尾斜杠。
3. 通过docker network inspect singverse-go-app_default查看私网IPAM，设置SINGVERSE_TRUSTED_PROXIES为实际内部Nginx地址/CIDR，不设置为任意来源。当前来源限流在多层代理模式按内层代理来源归组，账号限流仍生效；外层需另配真实客户端IP限流。
4. 使用显式覆盖配置启动：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml -f infra/docker/compose/compose.https.yaml up -d --build --wait
```

覆盖配置保持宿主回环绑定，仅在显式HTTPS部署下让内层Nginx声明https。后端只接受可信代理且Host与配置来源一致的请求，Cookie启用Secure。外部代理需配置HSTS与TLS策略；应用忽略未受信任的转发头。需要Compose2.24.4+支持端口覆盖。

公网部署后再在浏览器核对证书、Secure Cookie、CSRF、登录与MFA；本地检查不等于公网验收。TOTP不具备Passkey的抗钓鱼能力，不提供短信/邮箱找回、跨 NAS 身份。

## 验证入口

make frontend-check验证交互与请求行为；make go-backend-test验证SQLite真实HTTP与安全行为；make db-mysql-check在真实MySQL上重复账号测试，脚本自行创建隔离测试容器。make backend-check 验证真实 Go/Python 媒体授权往返与故障隔离。make docker-check只登录/退出当前实例，不修改安全设置；若已改密或启用MFA，通过SINGVERSE_CHECK_PASSWORD、SINGVERSE_CHECK_CODE提供测试凭据，避免在共享终端历史中明文填写。

make auth-docker-check使用现有本地镜像，在随机独立项目与新卷里执行改密、TOTP绑定、容器重建和真实TTY恢复，最后仅删除该测试项目和测试卷。它不更改默认 singverse-go-app 或旧 singverse-app 实例；需要POSIX宿主或Linux CI。跨语言向量见[auth-test-vectors.json](../contracts/openapi/auth-test-vectors.json)，其中密钥/密码是公开测试数据，禁止用于生产。

会话失效的401 Problem type为urn:singverse:auth:session-required，前端据此跳转登录；当前密码或动态码校验失败仍留在表单允许重试。

主密钥现也保护 WebDAV 媒体来源密码，使用独立 AAD `singverse:webdav:v1`。备份或替换密钥时须同时考虑媒体凭据；存在媒体密码密文时丢失/不匹配密钥会阻止正常启动，本地账号恢复不重置媒体密码。详见[媒体库管理](media-library.md)。

修改登录账号通过 `POST /api/v1/auth/username` 提交 `newUsername`、当前 `password` 及开启 MFA 时的 `code`。账号区分大小写，限 3～32 个 ASCII 字母、数字、下划线、点或短横线，以字母、数字或下划线开头，不能与当前账号相同。成功后撤销所有会话，使用新账号和原密码重新登录；MFA 配置保持有效。本机密码恢复保留当前登录账号。
