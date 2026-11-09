# AI 配置中心

AI 设置是管理员配置入口，不包含分类任务、审核或助手聊天。侧栏顺序为 AI 设置、服务状态、账号与安全；歌曲分类保留在曲库管理。内部页签分别为 `/system/ai/models`、`/system/ai/search`、`/system/ai/features`，`/system/ai` 默认进入模型服务。

## 模型与搜索服务

每类最多保存 100 套服务，同厂商可以保存多套。每套模型记录名称、厂商、协议、地址、手填模型 ID、密钥、启用状态、超时和结构化输出模式。超时范围 5～180 秒；预设不限制模型列表，也不证明特定账号或模型支持结构化输出。

预设包含 OpenAI、Anthropic、Gemini、DeepSeek、通义千问、豆包、智谱、月之暗面、硅基流动、OpenRouter、Ollama、LM Studio 和通用兼容接口。厂商预设与协议分离；模型调用支持 Responses、Chat Completions、Anthropic Messages、Gemini GenerateContent。除 Gemini 使用 API 版本基础地址并追加 `/models/{model}:generateContent` 外，填写完整调用地址。

结构化输出模式显式保存为 `JSON_SCHEMA` 或 `JSON_OBJECT`；Anthropic 原生仅支持 Schema。Schema 由业务调用方提供，JSON 模式附带相同结构要求；所有分类结果继续执行业务校验，拒绝未知歌曲/标签、非法字段和不完整输出。Anthropic 原生 Schema 不接受 maxItems，适配层将其移入描述并由分类结果校验执行数量上限，仍使用原生 Schema 模式。请求失败不自动切换协议或降级输出模式。普通生成测试成功不代表分类能力成功，分类结构化验证不代表分类准确率。

搜索服务支持 Tavily、Exa、Brave Search、博查、SearXNG、通用接口；分别适配请求与标题、链接、摘要响应。结果上限 1～10 条，可以配置多个服务并设置默认。默认变更不产生查询，测试须显式输入关键词；展示耗时、空结果、鉴权错误、限流或超时。本次不抓取正文，也不接入歌曲分类。

通用搜索接口接收 POST JSON `{ "query": "关键词", "maxResults": 5 }`，返回 `{ "results": [{ "title": "标题", "url": "https://example.test/page", "snippet": "摘要" }] }`。配置密钥时发送 Bearer，无密钥时允许本地免鉴权服务。只展示 HTTP(S) 结果链接。

## 功能绑定与兼容

功能配置使用稳定标识：`classification` 绑定模型、分类启用状态和 2～300 秒请求间隔；`default-search` 保存默认搜索服务。分类任务范围、维度和单任务自动应用选项不属于配置中心。后续功能可独立扩展自己的标识和参数，不依赖分类配置结构；助手展示配置现已独立提供，具体对话与工具能力仍在规划。

`202611070001_initial_schema.sql` 在 SQLite/MySQL 直接定义服务、功能绑定及事务协调表。从空库部署时不创建外部服务，功能默认关闭，管理员按需添加服务并绑定。旧分类配置接口保留兼容读写适配。

新任务冻结绑定服务快照。旧任务继续读取旧协议默认值及原加密凭据；变更绑定、分类参数或被绑定模型时暂停活动任务，不追溯改写快照。服务被任一功能引用时不能删除或停用，必须先替换或解除绑定。服务与绑定写入共享事务锁并使用 revision 防止陈旧编辑覆盖；业务 SQL 全部在 模块内嵌 SQL。

## 密钥与网络

读取只返回 `hasKey`，不回显密钥或密文。留空保留，明确清除才删除；更换厂商、协议、地址时要求重填或清除原密钥。密钥复用主密钥加密，按服务保存凭据用途并据此隔离；丢失主密钥时拒绝带加密配置启动。备份数据库时须同时安全备份主密钥。

Go 发起所有外部请求，保留 TLS 校验、禁止重定向、限制 128 KB 响应及配置超时。远程使用 HTTPS，HTTP 仅允许本机/局域网；地址禁止凭据、查询参数和 fragment。不要将此入口暴露给非管理员。错误不回传上游响应正文或凭据。测试按服务限制并发；HTTP 429 至少冷却 60 秒。

## 接口与验证

管理员会话和 CSRF 保护 `/api/v1/ai`：厂商目录 `GET /providers`；模型 `/models` 与搜索 `/search-services` 提供列表、新建、按 ID 修改/删除及 `POST /{id}/test`；功能 `/features` 提供列表和 `PUT /{id}`。模型测试 mode 为 `generation` 或 `classification`；搜索测试发送 query。完整字段见 OpenAPI。

隔离 HTTP 测试验证四种模型协议、六种搜索协议、JSON 模式、鉴权失败、限流冷却、超时、大小上限、非法响应、脱敏、默认搜索、绑定保护、迁移和快照。模拟协议测试与真实厂商联调分开记录，见 [验证记录](verification.md)。

协议实现参考 [OpenAI 结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)、[Anthropic Schema 限制](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)、[Gemini 结构化输出](https://ai.google.dev/gemini-api/docs/structured-output)、[DeepSeek JSON 模式](https://api-docs.deepseek.com/guides/json_mode/)。


## AI 助手展示

“功能配置 → AI 助手”打开 `/system/ai/features/assistant`。默认关闭，保存启用后在所有管理页面右下角显示入口；支持 PET 宠物和 CHAT 聊天框形态。内置本地 SVG“音符团子”及 13 款透明底图片形象：05 音符熊猫、31–36 二次元人物、37–42 科幻角色。前端 `pets.ts` 注册稳定形象 ID，与后端允许列表及 OpenAPI 枚举保持一致。图片位于模块 `assets/pets/` 并随构建打包，不依赖外部图片服务。选择列表采用自适应网格；图片形象使用本地透明 2×2 四帧图集播放眨眼、手部及配件动作，点击入口有短暂回应；拖动、页面后台时暂停。团子支持呼吸与眨眼。尊重减少动态效果偏好，动作图加载失败回退静态形象，配置列表和欢迎面板保持静态。配置通过 `assistant` 功能标识保存 displayMode、petId、enabled，由 SQLite/MySQL 的 `202611070001_initial_schema.sql` 定义，保留 revision 并发保护。

入口可展开、收起，支持 Escape 和焦点返回；窄屏面板限制宽高并内部滚动，尊重减少动画偏好。关闭配置即时隐藏入口，重新登录/刷新后读取服务端配置。只在管理布局挂载，不影响亲友 H5 或登录页。修改助手展示不暂停分类任务。

此阶段仅开放展示与入口，不绑定模型/搜索，不发送对话请求，不提供工具权限、不读取或写入曲库。面板明确显示“对话与工具能力尚未开放”；后续能力需另行设计接口与权限，展示开关不会自动授权工具。
