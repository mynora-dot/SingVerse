# Metadata Schema 1.0 字段字典

下表所有字段均可缺省；出现时必须有同名状态。缺省表示未提供；null 只有在 UNREAD/MISSING/NOT_APPLICABLE/CLEARED 状态下成立，不是空白字符串的同义词。SET/CLEAR 属于本机决定；AUTO 表示正式值来自自动采用的证据。每个字段状态修订不大于文档修订。

| 对象 | 字段 | 类型、单位及语义 | 合并／继承 |
|---|---|---|---|
| WORK | title, aliases | 标题；别名有序数组 | 每字段整体 |
| WORK | credits, externalIds | 词曲等署名；平台实体标识 | 每数组整体 |
| SONG | title, artists, credits, aliases | 正式标题；兼容署名；实体化有序署名；别名 | 每字段整体，版本可覆盖标题和署名 |
| SONG | workId, releaseId, tagIds | WORK UUID、RELEASE UUID、TAG UUID 数组 | 按引用关联，不以文本当主键 |
| SONG | album, releaseDate | 兼容发行名称；精确到年／月／日的日期 | 每字段整体 |
| SONG | referenceDurationMs | 平台曲目参考时长，整数毫秒 | 不继承为媒体实际时长 |
| SONG | cover | `{assetId: 原始字节SHA256}` | 版本未声明时继承；版本 CLEAR 阻断继承 |
| VARIANT | title, artists, credits, aliases | 该演绎的标题、实际署名、别名 | 显式字段覆盖歌曲的对应展示值 |
| VARIANT | kind | UNKNOWN / MV / KTV / LIVE / AUDIO / OTHER | 不从文件扩展名判断演绎 |
| VARIANT | languages | 有序语言标记字符串数组 | 不猜测语言 |
| VARIANT | timelineDurationMs | 此剪辑的时间轴长度，整数毫秒 | 同版本文件经确认后复用 |
| VARIANT | lyrics, lyricsAsset | 结构化歌词；歌词原文资源 | 时间轴仅适用于该版本，整体采用 |
| VARIANT | cover, externalIds | 版本图；平台实体标识 | 每字段整体 |
| MEDIA | sha256 | 完整文件原始字节摘要，可未知 | 不把 ETag 当摘要 |
| MEDIA | durationMs | 此文件探测出的实际时长，整数毫秒 | 播放用此值 |
| MEDIA | observation | 同次探测的容器、码率、音视频流等对象 | 整体替换，未知项不与旧流拼接 |
| MEDIA | audioRoles | 有序 `[{streamId, role}]`；role 为 ORIGINAL / BACKING / OTHER / UNKNOWN | 只在对应流关系成立时使用 |
| MEDIA | externalIds | 平台媒体实体标识 | 数组整体 |
| ARTIST | name, aliases, externalIds, portrait | 名称、别名、外部标识和原图资源 | 同名允许不同 UUID |
| RELEASE | title, credits, releaseDate, cover, externalIds | 发行标题、署名、日期、封面、平台标识 | 每字段整体 |
| TAG | name, dimension, externalIds | 标签名称、维度和平台标识 | UUID 关联 |

普通文本最多 500 字符，文本／UUID 数组最多 64 项；平台标识最多 128 项。署名为 `{artistId, name, role?, join?}`，保留顺序和实际名称，join 为后续署名间连接文字。`artists` 是旧接口兼容值；存在实体化 credits 时由适配器投影显示署名。`externalIds` 为 `{provider, entityType, id}`，ID 始终是字符串。

版本展示字段处于 UNREAD 或 MISSING 时继续继承歌曲值，RESET 没有可用证据也遵循此规则；PRESENT 覆盖歌曲值，CLEARED 和 NOT_APPLICABLE 阻断继承。字段状态与其原始空值仍保留在版本文档中，继承不会把歌曲值复制为版本的本地决定。

技术观测允许保留提取器提供的技术属性，但不得在公开包中携带命令、私有路径、URL、凭据、输入授权或 stderr。`streams` 是有序数组（最多 128），`frameRate` 为 `{numerator, denominator}`，分母 > 0；整数均在安全范围内。音轨以 `stream:索引`（可带 `:LEFT` / `:RIGHT`）引用源流，最多 128 项，同一流不能重复，主要原唱和伴奏各最多一项。技术时长使用 durationMs；旧秒值在适配边界转换。原唱／伴奏来自接收方的媒体校验，不因为共享资料中有声明就当成已验证。

歌词最大 256 KiB UTF-8 JSON：`timing=none|lrc` 使用 text；`timing=milliseconds` 使用有序 `lines:[{time, text}]`，time 为非负整数毫秒。`mpeg-frames` 保留原始帧序号，不得冒充毫秒；未知转换条件下仅作为原始证据。可选 language，行时间单调不减，最多 20,000 行。空歌词应通过 CLEAR，而非假造空的成功提取。

LOCATION 不进入资料文档：`id, mediaId, sourceId?, relativePath, state, observation, updatedAt` 保存在本机关系表。公开包只包含逻辑根目录和原始相对路径。路径不改变中文、空格、大小写或百分号；拒绝绝对路径、反斜线、控制字符和点路径段。URL 按段编码一次，不能把含 `%` 的文件名提前解码。观察中的 ETag、mtime 只在当前绑定下使用。
