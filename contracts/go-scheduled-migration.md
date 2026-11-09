# 后台定时流程迁移清单

本清单逐项对照 `go-migration-baseline.json` 的 23 个 Java `@Scheduled` 入口与当前 Go 实现。HTTP 路由覆盖不能证明这些流程已迁移；下面记录真实入口、合并后的执行位置与调度差异。Go 模块由应用生命周期统一启动与取消，不依赖 JVM、Redis 或外部任务队列。

| # | Java 定时入口 | Go 对应实现 | 执行与差异 |
|---:|---|---|---|
| 1 | `BackgroundTaskService.queueMaintenance` | `internal/background/service.go` 的 `Maintain` | 启动后 1 秒首次核对，再每分钟核对中断任务、聚合状态及保留期 |
| 2 | `BackgroundTaskService.queueProgress` | `internal/background/service.go` 的 `Project` 与各业务 `SetProjector` | Go 每秒批量刷新改为业务事务内同步投影；任务/子项状态与展示一起提交，不保留延迟刷新队列 |
| 3 | `AudioCatalog.refresh` | `internal/execution/batches.go` 的 `audioCatalog` | 改为首次请求构造、2 秒内复用的整库批量快照；无逐曲查询，不再另起每秒预热任务 |
| 4 | `AudioJobService.scheduledTick` | `internal/execution/audio.go` 的 `tickAudio` | 独立串行循环每 2 秒推进任务；Java 原为 3 秒 |
| 5 | `AudioJobService.scheduledDiscover` | `internal/execution/audio.go` 的 `discoverAudio` | 每 10 秒发现自动处理对象、检查节点能力与已有结果 |
| 6 | `AudioJobService.scheduledCollect` | `internal/execution/audio.go` 的 `collectAudio` | 每小时清理超过 24 小时的未引用、失效或临时音频衍生文件，保留媒体原件 |
| 7 | `AudioProcessingService.scheduledTick` | `internal/execution/batches.go` 的 `tickBatches` | 每 3 秒推进批次、同步逐曲任务与失败/取消状态 |
| 8 | `AudioProcessingService.collect` | `internal/execution/cache_management.go` 的 `maintainCache` | 合并到每分钟维护，在同一事务清理超过 24 小时的预览及预览子项；Java 原每小时 |
| 9 | `ClassificationWorker.tick` | `internal/catalog/worker.go` 的 `ProcessNext` / `loop` | 每秒认领一个分类对象；冻结配置和输入版本，模型 IO 在数据库事务外，结果带版本验证入库 |
| 10 | `LxDownloadService.pump` | `internal/downloads/worker.go` 的 `schedule` / `pump` | 每秒推进最多两首并行下载、发布恢复和批次收敛；来源发布锁与扫描共用 |
| 11 | `CoverAssetService.cleanup` | `internal/metadata/files.go` 的 `Cleanup` | 文件工作循环首次 1 分钟后、随后每小时清理未引用封面和过期候选/提交回执 |
| 12 | `MetadataFileService.tick` | `internal/metadata/files.go` 的 `filesLoop` / `ProcessFiles` | 每 2 秒推进持久文件待办和失败重试；仅写托管衍生目录 |
| 13 | `MetadataWorker.tick` | `internal/metadata/batches.go` 的批次工作循环 | 每秒推进平台元数据任务，保存结果并投影后台任务 |
| 14 | `PlaybackActivityService.scheduledMaintain` | `internal/playback/service.go` 的 `Maintain` | 首次 5 秒后、随后每秒维护播放租约、补写历史与热度 |
| 15 | `MediaPlaybackService.prefetchTick` | `internal/execution/original.go` 的 `PrefetchOriginal` | 改为每个活跃预取所有者的受取消 goroutine；前台读取优先，暂停检查采用 200 毫秒计时器，无全局每秒轮询队列 |
| 16 | `MediaScanScheduler.tick` | `internal/library/cron.go` 的 `schedule` / `runDue` | 每 30 秒检查标准扫描 cron、计算下次触发并认领；Java 原每 10 秒。cron 仍使用既定时区/分钟语义 |
| 17 | `SongCacheService.maintain` | `internal/execution/cache_management.go` 的 `maintainCache` | 每分钟执行缓存清点、延迟删除、策略水位、过期保护/任务和残留清理；Java 原每 10 秒，写入仍执行容量及保护校验 |
| 18 | `TranscodeService.tick` | `internal/execution/transcode.go` 的 `tickTranscodes` | 每 2 秒独立推进 HLS/离线任务，Python 继续承担媒体计算 |
| 19 | `WebDavMetadataService.tick` | `internal/metadata/remote.go` 的 `remoteLoop` / `RunRemoteNext` | 每 5 秒认领嵌入标签任务，保留来源组额度、退避及数据库外 IO |
| 20 | `WebDavMetadataService.prune` | `internal/metadata/files.go` 的 `Cleanup` | 合并到每小时清理，处理保留期外标签/订正/资源及未引用的托管文件 |
| 21 | `WebDavShareJobs.tick` | `internal/metadata/shares.go` 的 `shareLoop` / `ProcessShare` | 每 2 秒等待持久文件就绪并推进分享导出；保持检查点和分包 |
| 22 | `WebDavShareJobs.cleanup` | `internal/metadata/shares.go` 的 `cleanupShares`，由 `Cleanup` 调用 | 每小时清理过期分享任务/分包，仅处理托管分享目录 |
| 23 | `TvPrefetchService.expire` | `internal/party/prefetch.go` 的预取维护循环 | 每 5 秒验证会话和实际播放状态，取消过期预取；STOP、结束与撤销提交后立即取消 |

定时器周期属于内部实现，变更已列出；接口、持久任务状态和所有者边界仍依据原契约。上述源码映射不等同于在真实 NAS 上连续运行一小时或一天。相关模块的行为测试使用隔离 SQLite、可控 HTTP/计算夹具和显式维护入口验证状态迁移；迁移实测范围见 `../docs/go-migration-verification.md`。歌手自动抓图和 TV mDNS 等非 `@Scheduled` 生命周期工作另外由 `internal/catalog/artist_worker.go` 与 `internal/party/discovery.go` 承担，不属于本基线的 23 项计数。
