# 电视首页内置封面

这8张图片于2026-11-12使用内置 imagegen 工具生成，专用于 SingVerse 默认电视首页。JPEG 文件为生产资源，通过 `home_defaults.go` 的 `go:embed` 编入 Go 二进制，源码、原生构建与 Docker 构建均携带，不依赖外部图床或运行时生成。

首次启动仅在首页 `revision=0` 且封面列表为空时，在同一事务中写入8张图片并推进修订号。旧部署尚未配置首页时同样获得默认图片；已有配置不覆盖。管理员可修改名称、排序、停用、移除，删除后重启不恢复。8张内置图占用现有8张配额，上传前先移除不需要的图片。

交付图为1672×941（约16:9），JPEG质量85，总体积约2.3MiB。没有烘焙标题和品牌文字，左侧留暗部空间；实际显示遵循客户端当前裁切规则，当前TV推荐区使用方形缩略图，管理端使用16:9预览。

## 生成提示词集

共同要求：单张16:9横向音乐主题电视背景，电影感、精致写实摄影；主体偏右，左半暗色留白以承载白色界面标题；完整出血，无人物、无文字、无Logo、无水印、无界面。请求尺寸1920×1080，生成器实际输出1672×941，未放大。

| 文件 | 主题 | 场景提示词 |
|---|---|---|
| `01-blue-stage.jpg` | 蓝调舞台 | Midnight blue live music stage, elegant vintage microphone on stand at right third, subtle blue spotlights, atmospheric haze, distant warm audience bokeh. |
| `02-acoustic-evening.jpg` | 暖光吉他 | Wooden acoustic guitar at right near softly illuminated window, amber lamp, cozy intimate music room, deep walnut shadows, realistic wood grain, warm bokeh. |
| `03-moonlight-piano.jpg` | 月光钢琴 | Grand piano at right of intimate empty concert hall, polished black lacquer and ivory keys catching soft moonlight, deep indigo and silver, quiet romantic atmosphere. |
| `04-vinyl-memories.jpg` | 黑胶时光 | Vinyl record player on right, plain unlabeled record center, fine tonearm, vintage wood cabinet, soft copper light, deep teal evening studio, nostalgic soulful mood. |
| `05-neon-rhythm.jpg` | 霓虹律动 | Elegant flowing violet, cyan and magenta light trails resembling musical vibration across right half, glossy dark floor reflections, midnight plum, sophisticated photographic light painting, no musical note symbols. |
| `06-sunset-melody.jpg` | 落日旋律 | Microphone and acoustic guitar on small wooden lakeside stage at right, still water and mountain silhouettes, peach amber twilight dissolving into deep blue, intimate outdoor concert. |
| `07-jazz-night.jpg` | 爵士之夜 | Brass saxophone on stand at right of empty jazz lounge, amber spotlight grazing brass keys, burgundy velvet curtain, smoky wine-black shadows, tiny golden bokeh. |
| `08-starlit-echo.jpg` | 星空回响 | Dreamlike open-air stage under midnight star field, contemporary microphone at right with silver rim light, subtle turquoise aurora and mountain horizon, tiny warm stage lights. |

PNG生成原稿保留在工具的默认输出目录；仓库只保存实际内嵌的优化JPEG。更换默认资源时同步主题清单和图片内容版本；默认资源变更不会替换已经初始化的用户图片。
