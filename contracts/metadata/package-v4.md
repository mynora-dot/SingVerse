# 共享容器 v4

只分享资料，不包含媒体文件。发送 ZIP 与发送网盘链接是分开的用户操作；接收方先把媒体放到自己的本地目录或 OpenList，再绑定自己的来源。根地址、用户名、密码、临时直链、审计和本机任务不得出包。

## 完整包布局

```text
index.json
part-000001.zip
part-000002.zip
...
```

实际分片文件名以 index 清单为准。每个内部 ZIP 含 `manifest.json` 和可选 `chunks/<sha256>.bin`。index 的 `format=singverse-share-set`、`version=4`、`metadataSchema=1.0`；内部清单 `format=singverse-media-index`。均含 collectionId、字符串 collectionRevision 和 baseId；index 的 id 对应内部清单 setId。无 baseId 是完整包；增量的 baseId 必须与接收方已完成的基线对应。

index 列出 parts、deletions 及 files（每个文件的 sha256 与 size）。`contentDigest` 等于移除该字段后的整个 index 进行 JCS 规范化再 SHA-256。各分片原始字节摘要必须匹配；记录文档按 Schema 校验。相同 collectionId+collectionRevision 不得出现不同 contentDigest。完整包不依赖发送方其他任务文件或数据库。

内部清单条目包含 `rootId:"media"`、`path`、可空 `size`、`mediaId` 和 documents 数组（MEDIA、VARIANT、SONG 及必要关联对象）。每条独立记录必须带完整关联；同一对象可出现在多条记录中，但相同 UUID 的内容必须一致。资料文档 revision 是发送方对象修订，与 collectionRevision 不是同一个计数。未知可选 extensions 保留。

resources 数组描述 `id,mime,size,chunks:[{id,size}]`。id 是原始完整附件摘要；chunk id 是块字节摘要。资源去重，块不超过 16 MiB；附件不超过 32 MiB，块按数组顺序拼接必须恢复相同原始字节。附件块可以先于记录放在前面的分片；记录出现时所有引用附件必须完整可用。单片压缩及展开总量均不超过 32 MiB，JSON 清单不超过 16 MiB；完整上传沿用 8 GiB 和既有条目／展开保护。校验器逐片读取，不把完整包解压到媒体目录。

删除清单仅提示共享方移除的路径；不删除接收方资料或本地屏蔽决定。资料包不包含账号和系统密钥，因此不能替代完整系统备份。

## 地址绑定与更新

每个包当前仅有逻辑根 `media`；用户选择自己来源的根地址。原始相对路径可以做显式目录前缀映射；映射后重复或越界立即拒绝。预览显示组合后的接收方路径，不凭同名歌名自动匹配。

新 NAS 保留包内稳定 UUID。已有路径已经扫描时，将远端对象映射到本地对象；同一 NAS 另一来源的重复导入保存独立映射，不错误沿用其他来源的播放绑定。映射按接收来源、共享系列、远端对象持久化。接收方后续人工调整的父级关联受到保护。

上传结束后后台解析、预览、确认、每批最多 100 条提交，再按接收方目录核验。每批检查点持久化；中途停止明确保留已完成部分；全部条目提交完成才推进共享基线，核验可继续后台运行。目录存在表示 PRESENT，不表示播放已验证。MISSING 是确实缺失，MISMATCH 是大小冲突，UNREACHABLE 是连接／权限／限流等访问失败，具体错误保存在任务明细；访问失败不删除资料。

v2/v3 继续走兼容适配器。v2 没有可靠共享系列，不能据它推断后续更新关系。v3 的数字修订号转换为内部字符串语义，旧空白值不擦除。v4 要求修订为字符串、二进制附件与完整清单校验；不得将其回退为忽略未知字段的旧解析。
