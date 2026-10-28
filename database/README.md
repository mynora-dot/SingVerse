# 数据库约定

当前业务表包括单管理员认证的 auth_account、auth_token、auth_recovery、auth_throttle、auth_event，以及媒体来源配置 media_source、解析规则配置 filename_parser_config。本地歌曲文件名索引保存于 media_song；队列和播放表尚未实现。

migrations/sqlite与migrations/mysql是语言无关的dbmate版本化SQL；schema_migrations记录已应用版本。Java启动前执行迁移，Go未来复用同一迁移与数据含义，不以Entity定义表。不要修改已应用的迁移或自动执行down；SQLite/MySQL切换不搬运数据。

账号字段、密钥格式、初始化及恢复见[认证说明](../docs/authentication.md)，连接和Profile见[数据库策略](../docs/database-strategy.md)。账号、会话、恢复码摘要和TOTP密文均属于敏感数据；数据库和主密钥分别存储、配套备份，禁止提交数据库及WAL/SHM或密钥。

新增 `media_source` 保存真实媒体来源配置，SQLite/MySQL 迁移版本 `202610270002`。WebDAV 密码加密，不返回明文或密文；主密钥配套备份。本地扫描索引保存于 media_song，详见[媒体库管理](../docs/media-library.md)。

`202610270003` 新增 `filename_parser_config`，单例 id=1，保存规则 JSON、乐观并发 revision 和 UTC 更新时间；没有已保存记录时返回内置默认规则，预览不创建记录。SQLite 使用 TEXT，MySQL 使用 MEDIUMTEXT，JSON 的字段和语义相同；表不包含歌曲索引。更新必须带预期 revision，冲突返回 409，不覆盖其他窗口配置。配置损坏不静默回退；内置规则以稳定 builtinId 引用，见[规则说明](../docs/filename-rules.md)。

规则配置的 rules_json 现支持 schemaVersion=2，保存独立 definitions 和 profiles 有序引用；规则 enabled 不再是定义属性。旧数组 GET 时只投影默认方案，首次修改才通过全局 revision 原子升级，ID/名称/表达式/顺序/启停保持。没有新增物理表或改写 `202610270003`；受引用定义禁止删除，引用检查和保存使用同一文档快照，避免悬空引用。详见[规则与方案](../docs/filename-rules.md)。

`202610280001` 为媒体来源新增 parser_profile_id，旧记录默认绑定 default；`202610280002` 新增 media_song，保存来源相对路径、文件属性、方案/配置版本、命中规则与解析结果 JSON。来源移除会在同一事务清理索引，原始媒体不变；完整扫描以事务替换该来源索引。既有来源扫描突然得到零媒体文件时保留索引并返回 409，防止掉挂载被当作全删。

`202610280003` 为 media_song 增加 needs_review 并从既有结果 JSON 回填，供歌曲管理进行数据库分页筛选；歌曲列表通过来源关联显示名称。扫描写入同时保存复核标志与 JSON 结果，仍为 MyBatis XML 显式 SQL。

WebDAV 后台扫描迁移 `202610280005_webdav_scan.sql` 允许 `media_song.file_size/modified_at` 为空，并新增任务、目录待办和歌曲暂存表；任务失败不改变正式索引，成功在事务内从暂存表发布。SQLite 通过重建歌曲表保留已有行及索引，MySQL 修改列可空。迁移回退保留可空属性，不填造缺失值。详见[媒体库管理](../docs/media-library.md)。
