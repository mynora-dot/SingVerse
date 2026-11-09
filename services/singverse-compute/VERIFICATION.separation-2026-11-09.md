# 快速伴奏引擎实施验证

日期：2026-11-09（Asia/Shanghai）。本次为本机开发与合成素材验证，没有更新 NAS 部署，也没有新增六类硬件的真实歌曲 Benchmark。实施及部署步骤见 [AUDIO-SEPARATION.md](AUDIO-SEPARATION.md)。

## 环境与口径

- Apple M2，8 个逻辑核心，16 GiB 内存，原生 macOS 26.6.2，Python 3.12。
- 服务原有 NumPy 1.26.4／Torch 2.5.1 环境保持；独立 CPU 环境为 ORT 1.20.1、NumPy 1.26.4、SoundFile 0.13.1。
- 独立 MLX 环境：mlx-audio-separator 0.1.20、MLX 0.32.3、NumPy 2.2.6，实际安装后冻结完整传递依赖并通过 pip check。
- 转换环境：CPU ORT 1.20.1、ONNX 1.17.0、OpenVINO 2025.4.0，独立安装并通过 pip check。
- Java 项目目标版本仍为 21；本机现有可用 JDK 为 Homebrew 26.0.2.1，本轮测试使用它，没有更换全局 Java 或宣称完成 JDK 21 真机验证。
- 固定模型 UVR_MDXNET_9482，SHA-256 `9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d`，29,704,738 字节。

## 功能与故障边界

已通过真实 FFmpeg、固定 MDX 权重及隔离 Java/compute 进程验证：

- DSP 中心抵消：16 秒“居中 440Hz + 侧向 880Hz”立体声 FLAC，输出双单声道，保留侧向分量；12 秒试听偏移 1.4 秒，源文件 SHA 不变。
- 单声道、双单声道拒绝发布伴奏；近静音错误保留 DSP_NOT_SUITABLE。FFmpeg 提前关闭回源连接时不再把本地 BrokenPipe 误报成上游连接失败。
- 已确认 FLAC 音轨直接复制，解码 PCM 与源一致；明确确认的 LEFT 声道提取成功。未提交 sourceConfirmed=true 的源声道请求被 Java 拒绝。
- Java 管理单曲试听、异步批量整曲、FLAC 下载（检查 fLaC 文件头）、伴奏版本切换；试听和 FLAC 不成为当前播放伴奏，已有成功版本保留。
- 新增 DSP/MDX/HTDemucs、试听/整曲、AAC/FLAC 配方去重，源指纹改变使旧版本不可激活；批量不把 DSP 结果当作 AI 结果跳过。
- 固定 MDX 的独立 ORT CPU 完整处理成功；指定本机不可用的 CUDA 请求，最终使用 CPU、同一模型摘要，保存 ACCELERATOR_UNAVAILABLE。
- 将加速解释器配置为不存在的绝对路径，并清空 CUDA/ROCm 可见设备，CPU CLI 仍独立完成资格试跑与 AAC 输出；其输出 SHA 与正常 CPU 路径一致。CPU 专用探测不执行 nvidia-smi、不枚举 OpenVINO GPU、不导入 MLX。
- M2 的 MLX 与 CoreML EP 均完成合成数值资格。MLX 相对 CPU 波形 L2 误差约 `6.18e-6`；CoreML 约 `8.99e-4`，均低于 2% 数值门槛。CoreML 是混合图，不能证明全图 GPU 或 ANE。
- 故障注入测试覆盖加速初始化、provider、worker 崩溃、内存预算错误只重放一次 CPU；CPU 再失败、取消和输入 I/O 错误不重试，不自动改成 DSP。
- 资格键区分相同环境下不同 EP，绑定模型、配方、运行库、设备/驱动和执行源码摘要；高级档 CPU 可路由到独立解释器，不要求服务环境安装 Torch。

NumPy 与原 Torch 频谱实现比较：同一随机输入，STFT 最大绝对误差约 `2.86e-6`，ISTFT 约 `1.19e-7`；对应断言在单元测试中。此结果验证数值兼容，不代表真实人声分离质量。

## 合成素材重复运行：不是歌曲性能结论

输入是上述 **16 秒合成 FLAC**，SHA-256 `75232e5842d9264fbef8f2605e08d65ce8203a42898376615cf14521fe73d419`。四配置顺序执行，每项 2 次预热、5 次有效重复，输出 AAC 192k。以下是本地读取、解码/分离/编码、校验和摘要的实测，不含 Java、HTTP 回源与上传发布。

| 配置 | 5 次中位耗时 | 中位 RTF | 有效重复中的最大采样 worker RSS |
|---|---:|---:|---:|
| DSP CPU | 725.555 ms | 0.045347 | 未采集 |
| MDX ORT CPU FP32 | 3641.418 ms | 0.227589 | 1,105,264,640 bytes |
| MDX MLX FP32 | 1367.314 ms | 0.085457 | 219,414,528 bytes |
| MDX CoreML EP，设备选定精度 | 1629.101 ms | 0.101819 | 2,139,914,240 bytes |

RSS 是父进程对 worker 的采样，**不是统一内存/GPU 内存总量**；不能由这张表推断框架完整内存或功耗优劣。没有能耗、温度、降频数据。样本只有 5 个，工具正确输出 P50/P95=null。纯音不能代表模型在真实歌曲上的残唱、鼓/贝斯损失或伪影，不能外推四分钟歌曲速度，也不据此选择六类设备的最快后端。

前三项源码摘要为 `e69e6f9767ed5ded98d89a8bcb8e2ce0b9b821ab6fa6b8f83094259119d4918d`；CoreML 为 `beb8d91d646a63420432a0d0d48e5839b13b82b93f1c18f8448b4cad39ab01a4`。期间增加了 TensorRT 元数据/库加载代码，以上四路线的频谱与 forward 算法未改变。各自原始 JSONL 保存真实源码与环境摘要，不伪称同一提交的正式对照。

CoreML 的一次完整处理 profiling 计数为 CoreMLExecutionProvider=84、CPUExecutionProvider=81；这是执行事件计数，不是算子耗时百分比，更不是 ANE 利用率。MLX 主机/设备数组转换与执行分别记录；CPU/其它无法拆分的拷贝耗时为 null。

原始报告由 `scripts/benchmark-separation.py` 输出至本次临时目录 `singverse-finalbench-*` 的 environment.json、runs.jsonl、runs.csv、summary.json；不把模型、日志、凭据或派生产物放入源码。重现时使用自备授权素材与新输出目录。

## 转换工具

- 固定 ONNX → OpenVINO FP16 压缩权重转换成功，写出 XML/BIN 与 conversion.json，明确 productionApproved=false。
- 独立 CPU 对照工具已用同一合成素材实际运行；候选波形相对 FP32 误差约 0.002395。这里只验证转换和报告流程，未通过真实歌曲音质门槛，也不宣称 CPU 全图采用 FP16。
- 沙箱内 OpenVINO JIT 分配失败；在获准的正常本机执行环境重新运行成功。没有关闭证书验证或修改全局系统配置。
- 静态 INT8 QDQ 校准脚本、固定频谱 tile 生成和转换候选对照报告工具已提供。没有代表性授权校准/验证曲库，本轮没有生成或推广新的 INT8 生产模型。

## 回归检查

| 检查 | 本次结果 |
|---|---|
| make compute-check | 223 项，8 项显式模型/真机集成测试按条件跳过，其余通过 |
| 新 Java/compute 真实双进程测试 | 显式提供构建 JAR 与 MDX，1 项通过；包括实际 CPU 和不可用 CUDA 回退 |
| Java 完整 make java-backend-test | 629 项通过，使用 reuseForks=false 与 fork 超时限制 |
| 最后伴奏/批量/认证契约定向验证 | 49 项通过，含新增 CPU 失效入场测试，并完成 verify 打包 |
| 前端 npm test | 48 个文件、401 项通过 |
| make frontend-check | 类型检查与构建通过；仍有既有大 chunk 提示 |
| make db-fresh-check | SQLite 空库、重复迁移、增量保留、逐级回滚和重建通过；76 表、62 显式索引 |
| make docker-config-check | 29 组配置通过；未启动部署 |
| Docker CPU 镜像构建 | 未完成：拉取固定 Python 基础镜像元数据时 Docker Hub DeadlineExceeded，尚未执行依赖安装层 |
| git diff --check、脚本语法/JSON | 通过 |

首次全量 Java 有一项既有 WebDAV 五秒超时断言失败；隔离重跑与后续整轮 629 项均通过，未删除或放宽该断言。端到端 fixture 等待现有异步曲库索引与批次准备完成，不把其暂时 503 当成任务失败。

## 仍需现场验收

NVIDIA CUDA/TRT、Intel GPU、AMD MIGraphX、RK3566 新统一引擎、M1/M3/M4/M5 的新配方未获本轮真机资格；历史 A3000/780M/RK3566 记录不能替代这些验证。DirectML/Windows、Linux 容器独立 CPU 启动、真实 MySQL 迁移和容器创建前驱动故障恢复也未实测。

未做 30 首真实授权歌曲盲听/参考分轨质量、功耗、100 首或两小时稳定性及并发视频播放真机验收。Spleeter、Inst_HQ_3、REPET、原生 Core ML/ANE、demucs-mlx 与专用模型训练仍为后续实验；点歌即时生成和边生成边播放不在此首版交付范围。
