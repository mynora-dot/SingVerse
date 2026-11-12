# TV 热歌与演唱历史

TV 首页、热歌页和历史页读取正式 Go 播放记录，不以扫描入库、入队、下载、缓存命中或封面点击代替演唱。接口契约见 [OpenAPI](../contracts/openapi/singverse.openapi.json)。

## 接口与范围

| 接口 | 数据 | 范围与排序 |
| --- | --- | --- |
| `GET /api/v1/tv/home` 的 `popularSongs` | 最多 8 首当前可点 `TvSong`，附 `playCount/effectivePlayCount/completedCount/lastPlayedAt` | 全 NAS 最近 30 天 TV + MOBILE；有效次数倒序、最近开始时间倒序、歌曲 ID 升序 |
| 首页 `recentlyPlayedSongs` | 最多 8 首当前可点 `TvSong`，附 `lastPlayedAt/lastPlaybackStatus/lastHistoryId` | 当前已授权设备所有会话的终态历史，每首只取最近实例；开始时间倒序、历史 ID 倒序 |
| `GET /api/v1/tv/popular?page=0&size=20` | `items` 与首页热歌同结构 | 固定最近 30 天，在分页前排除当前 TV 曲库不可见歌曲 |
| `GET /api/v1/tv/playback-history?page=0&size=20` | 逐次历史实例，包含快照与可空的当前 `song` | 当前设备全部历史会话，不设时间截断、不按歌曲去重；开始时间倒序、历史 ID 倒序 |

分页统一 `items/totalElements/page/size`，默认 20，最大 100，页码从 0 开始、最大 1000000。范围不来自调用方的设备或会话参数；传入 `deviceId/sessionId/ownerSessionId` 返回 400。三路由均要求有效 TV 设备 Bearer；管理员 Cookie 不代替 TV 授权，亲友参与者 Bearer 返回 403。读取返回 `Cache-Control: no-store`。

首页保留 `recentSongs` 的既有含义：最近扫描入库歌曲，兼容旧客户端；它与 `recentlyPlayedSongs` 分别读取，不改名冒充演唱历史。没有真实数据时数组为空。

## 统计口径

有效播放复用正式播放域规则：实际播放累计达到 30000 毫秒，或者已知时长小于 30000 毫秒、实际播放达到该时长且状态为 `COMPLETED`。阈值读取 `played_ms`，不读取进度条位置或入队次数。热歌只统计 `TV/MOBILE`，不计 `ADMIN` 试听；同一播放实例只计一次。已达到有效阈值的活动播放也可进入热歌。

热歌窗口按实例 `startedAt` 落在服务端当前时刻之前 30×24 小时至当前时刻之间，两端包含；`playCount` 包含窗口内该歌所有 TV/MOBILE 实例，`effectivePlayCount` 只计有效实例，`completedCount` 计 `COMPLETED`。至少有一次有效播放才进入榜单。`lastPlayedAt` 是窗口内最近实例的开始时间，不是完成时间；它不是已有缓存热度 `heat` 或指数衰减分数。

历史只包含 `COMPLETED/SKIPPED/STOPPED/ERROR/INTERRUPTED`，排除活动 `PLAYING` 记录；所有终态均保留，不把跳过或失败显示为“已唱完”。首页按歌曲选择最新终态实例，当前活动播放不会覆盖它。历史按实际归属的 TV 会话解析设备，跨场查询依然限制同一授权设备；MOBILE/ADMIN 即使出现相同会话字符串也不能混入。

## 当前歌曲与快照

“可点”沿用 TV 正式曲库索引规则：媒体核验状态为空或 `PRESENT`；不宣称经过设备解码验收。首页与热歌均在统计分页前应用此可见集，并返回当前标题、歌手、封面与 revision。与现有 TV 曲库相同，索引会在失效后重建并有 30 秒缓存。

独立历史实例包含 `id`（历史 ID）、`songId`、快照 `title/artists/versionLabel/sourceName`、`status/startedAt/endedAt/playedMs/durationMs/effective`，另附 `available` 和 `song`。歌曲仍可见时 `available=true`、`song` 为当前 `TvSong`；移除或核验失效后 `available=false`、`song=null`，保留快照只读并计入分页总数。客户端点歌只使用当前 `song.id`，不能用历史 ID 或快照绕过曲库校验。

响应不公开请求者、设备名、会话 ID、设备 ID、来源路径或凭据。热歌公开的是本家庭 NAS 的歌曲聚合，不携带其他设备的历史实例。设备撤销后沿用既有 Bearer 撤销校验；管理员完整历史仍走原管理员接口，授权范围没有放宽。

## 存储与验证

`internal/playback/tv.go` 与对应嵌入 SQL 拥有统计和历史读取；`internal/party/recommendations.go` 拥有设备身份、设备会话解析及当前曲库投影。调用跨域读取复用同一 `store.DBTX`，不在 SQLite 单连接事务内另取连接。可见歌曲与会话列表通过绑定 JSON 行集传递，避免大曲库触发 SQL 参数数量上限。

新增迁移 `202611120001_tv_playback_reads.sql` 仅增加 `(client_type,session_id,started_at,id)` 索引，不重写基线、不回填虚构记录。行为测试覆盖有效阈值、30 天边界、全 NAS TV/手机聚合、管理员排除、设备隔离、跨场、活动记录排除、历史去重、缺失只读、排序分页、参与者拒绝及无数据空态。
