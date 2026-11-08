# 2026-11-08 RK3566 / fnOS 实机部署与硬件验证

## 环境与部署

- 主机 `10.100.1.115`，设备树产品名 `OneThingCloud OEC`，`compatible` 包含 `rockchip,rk3566`；ARM64、fnOS 内核 `6.18.18.c1196-trim`，内存约 3.8GiB。
- 本次独立 Compose 项目 `singverse-nas-test`，目录 `/vol1/1000/docker-test/SingVerse`；管理地址 `http://10.100.1.115:18080/admin/`。Java 21 直接托管本次完整 Vue 产物，SQLite、密钥、计算认证、缓存和模型使用新命名卷。未替换既有 `singverse-rk3566` 计算节点或其他 NAS 应用。
- NAS 无法正常解析外部包源；依赖在本机下载并通过 SSH 上传，离线构建。Java 使用 NAS 已缓存 JRE21 镜像作为运行层，compute 使用已缓存 ARM64 UVR 环境，覆盖当前项目代码及锁定基础依赖。此处是现场离线组装，不能据此声称标准 Dockerfile 全量联网构建通过。
- 原始 `music` 只读挂载给 Java。NAS 文件 ACL 要求 UID1000，故仅将本项目 Java 设置为 UID1000:1001 并调整本项目新数据/密钥卷所有者；未放宽原始媒体权限。计算服务为 UID10001，只有指定设备映射及组权限，无特权模式。

## VPU / RKMPP

1. H.264 编码器资格试跑通过：首次管理 API 约293ms，最终修复镜像复测749ms通过。HEVC 编码器已安装但样本试跑未通过；AV1 编码器未安装，不计为可用。
2. 用户上传的《一场游戏一场梦》MKV，256.52 秒、H.264 720×480、29.97fps、两条 AAC 双声道音轨。通过真实管理员登录和 Java 编排，从 30 秒位置强制转为客户端 640×480 上限的 HLS。
3. 验收时关闭 CPU 自动降级。实际返回 `backend=rkmpp`，`pipeline.name=rkmpp-rga`、`hardwareDecode=true`、`zeroCopy=true`、空 `fallbackReason`。输出 H.264 640×426，所选 AAC 音轨保留；就绪采样速度约 2.32×、RSS约 37MiB，仅为起播窗口，不能当成整曲平均性能。
4. Java 授权下载 master、视频和音频 playlist，以及各自首个 TS（373,368 / 51,512 字节）；本机 ffprobe 确认视频规格，两个首片 ffmpeg 完整解码退出0。另从0秒完成整曲转码，最终 `SUCCEEDED`、速度读数6.01×、峰值RSS约36.8MiB；视频/音频各129片、清单均含ENDLIST，共258个TS全部授权下载，整份HLS双流ffmpeg完整解码退出0。HLS视频时长约256.12秒，与源容器256.52秒存在约0.40秒边界差，尚未做视听同步判定。测试会话随后取消；未验收电视实时播放、HEVC、HDR、色调映射或微信。

## NPU / AI 伴奏

实际 NPU 的 `card0` 和 `renderD128` 均绑定 RKNPU 驱动；主机不存在 renderD129。RKNPU 驱动0.9.8，主机 `librknnrt.so` 2.3.2。Lite2 的容器检查固定查 renderD129，故将真实 renderD128 同时映射为容器内该别名；运行库还需要真实 NPU 主设备 card0（video组44），仅映射 render 节点时初始化失败。主机 render组为105。这里的路径来自实际驱动检查，不能复制到其他 NAS 后直接假定正确。

### 转换与运行环境

- 固定 UVR_MDXNET_9482 ONNX：29,704,738字节，SHA256 `9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d`。
- 无网络隔离转换容器：Toolkit2 2.3.2 / Python3.12 / Torch2.2.0；ARM64 toolkit 元数据要求 Torch≤2.2.0，与服务的 Torch2.5.1 分开。原始库缺少libGL，改装OpenCV headless4.10.0.84；补齐setuptools75.8.2提供pkg_resources。verbose转换工作目录需可写，否则中间ONNX保存失败。
- 非量化转换成功，目标rk3566，RKNN文件20,046,968字节，SHA256 `b67e7334e43f4b294838ee65b4af05c15cdb0d84209e804f79d65a92ac509180`；工具报告内部内存102,528KiB、权重14,507.6KiB。完整模型与manifest保留在 `test-output/rknn-converted-20261108-4/`，另安装到本项目models卷。
- Lite2自身需读取 `/proc/device-tree/compatible`，此 fnOS 中该符号链接指向 `/sys/firmware`；Docker默认遮蔽及AppArmor限制使其不可读。应用通过 `/run/singverse-hardware/device-tree` 可以识别硬件，但这不代表SDK能初始化。另需将主机 `librknnrt.so` 只读挂入容器；仅装Python包不足以运行。
- 默认隔离下的管理API资格试跑明确失败，保留失败状态；未将隔离CLI成功写成正式服务READY。自动审批拒绝持久关闭本计算容器的AppArmor和系统路径保护，理由是原授权为功能测试，持续降低隔离需要用户明确授权。该持久变更尚未应用。

### 临时隔离实测通过

替代测试使用无网络、只读根文件系统、只读输入/模型/运行库、UID10001、删除全部capability、no-new-privileges的临时容器，仅该临时容器调整上述两项隔离。显式映射真实NPU设备，输出只写本次test-output目录；不连接业务API、不伪造管理端资格，不启用CPU自动降级。CPU仍负责STFT、拼接及数据准备。最新RKNN适配器通过只读源码挂载验证；同源修复已构建到测试compute镜像。

| 任务 | 实际输入 | 耗时 | 峰值进程RSS | 结果 |
| --- | --- | --- | --- | --- |
| NPU短片 | 8秒双声道合成波形 | 容器存续约85.18秒 | 未采样 | 退出0，人声/伴奏均为8秒、双声道、有限非静音输出 |
| NPU完整歌曲 | 用户FLAC解码所得112.066667秒WAV | 662.046秒（约11分2秒） | 706.14MiB | 退出0，无OOM，完整两份衍生WAV |
| ONNX CPU参考 | 同一真实歌曲的前8秒 | 104.705秒 | 1,242.87MiB | 退出0，无OOM，仅独立CLI对照，不是管理端CPU降级 |

- NPU全曲输出各4,942,140帧、44.1kHz、双声道PCM16，每份19,768,604字节；数据均有限、非静音，人声/伴奏RMS约0.07923/0.06695，重建原信号RMS误差约3.05e-5。
- 伴奏SHA256 `720d86c537a8b7857bbd80602048ca385157ce97fc345edfe23a8b3f88318992`；人声 `ec038eb632f95afdf8ae17488e03fe6a635983c86dae25c511f238d79b16d3aa`。两份文件通过SSH取回后摘要一致，本机ffmpeg完整解码均退出0。
- NPU内核负载真实升高，采样峰值97%；样本包含任务前后空闲，不能作为平均利用率。全曲速度约0.17×，适合提前离线生成，当前模型不能实时分离。本次含短时CPU对照及视频并行窗口，不是独占硬件基准。
- 首个相同模型分块（257,024帧，5.828209秒）的NPU/ONNX CPU输出比较：人声相对RMS误差0.0982%、相关系数0.99999957；伴奏误差0.1246%、相关系数0.99999922。该对照验证转换数值接近参考，不等于全曲或主观音质验收。
- 首次全曲CPU对照在768MiB容器上限附近占满预算，主动停止，未宣称该任务通过；NPU完成后单独以1280MiB上限跑8秒对照通过。更早管理端CPU HTDemucs的8秒资格试跑在1GiB进程预算下失败 `Media process exceeded memory budget`，两种CPU路径/模型不混淆。
- 原始FLAC解码时出现invalid sync/frame提示；生成WAV时长/帧数与源声明一致。本次校验针对生成WAV和分离产物，未声称原始FLAC严格无解码错误，也未修改原件。
- 实际产物位于NAS `test-output/rknn-real/htdemucs/input/{vocals,no_vocals}.wav`，指标为 `test-output/rknn-real/validation.json`；所有路径相对本次部署目录。

### VPU / NPU混合窗口

NPU全曲仍在运行时，通过Java请求新的480×320 HLS，从45秒处起播。首次请求命中已有全曲缓存，不计为硬件并行证据；改变客户端上限后产生新cacheKey，执行状态RUNNING、实际后端rkmpp、hardwareDecode/zeroCopy均true、无CPU回退。视频和音频首片均授权下载、本机完整解码通过，随后取消视频会话。只验收该短时并行窗口，不代表长时间混合压力测试。

### 清理与正式接入待办

已保留转换、失败尝试、推理和负载报告；删除全部本次临时转换/推理/CPU对照容器。正式测试Java与compute保留运行，最终健康、RestartCount均0、无OOM；compute仍为docker-default AppArmor，未应用被拒绝的安全设置。两首原件SHA256前后均一致；主机card0/renderD128及MPP/RGA权限保持原值。

正式NPU接入的未应用差异已在工作机 `/private/tmp/singverse-rknn-review-20261108/proposed-rknn.patch` 准备好供审阅：只对此compute容器调整AppArmor和systempaths，添加no-new-privileges；删除默认capability，仅保留启动时设备节点权限与降权所需CHOWN/SETUID/SETGID，服务继续降为UID10001。增加NPU card0和只读运行库挂载，并在容器内入口处理card0/设备别名权限；不修改主机全局策略或其他应用。取得明确授权后仍须真实通过Java模型资格试跑、提交歌曲任务及授权伴奏下载，不能复用本条CLI结果替代。

## 本次兼容修复与本机检查

- RKNN Lite2 初始化会污染 Python 标准日志等级名称，使随后 Torch 导入报 `Unknown level: WARNING`；适配器在初始化后及失败时恢复原有等级映射。新增实际问题回归，保持失败时释放运行时。
- 板卡产品名可能不含 Rockchip 型号；硬件识别同时读取 `model` 与 `compatible`。板型读取支持 `/proc/device-tree`、sysfs及容器只读设备树挂载；RKNN 环境和执行目标检查共用该读取方式。
- Docker 默认屏蔽 `/sys/firmware`，将设备树挂在该目录内部仍不可读。Rockchip 与 RK3566 RKNN 模板改挂 `/run/singverse-hardware/device-tree`，保持只读；已在本次容器实际识别 RKMPP/RKNN。
- `make compute-check`：207项，7项条件跳过，其余通过；新增 OEC/compatible、缺失 model、容器路径、非 Rockchip 与 RKNN 目标检查回归。
- `make java-backend-test`：JDK21，616项通过，无失败/错误/跳过；包含完整前端构建。默认 JDK17 无法编译，改用本次命令的既有 JDK21，不修改全局环境。
- `make docker-config-check`：29组通过；只验证配置，不等同于所有镜像/硬件验收。
- 专用无头浏览器实际登录和管理页展示通过，曲库显示两个上传文件。伴奏功能按真实资格状态禁用生成；未绕过门禁。

## 后续性能定位与量化对照

- 在同一歌曲前8秒、相同2核CPU限额、1个DSP线程、1280MiB容器限额下分段计时：Python依赖导入10.271秒、模型初始化1.340秒、分离阶段63.791秒，其中两次RKNN推理调用合计62.650秒（31.616 / 31.034秒），约占分离阶段98.2%。STFT/ISTFT、文件读写及张量准备合计约1.14秒；该计时未细分SDK内部的NPU算子与CPU算子，不能全部记为NPU计算时间。
- 原FP16转换报告包含46个CPU Transpose；NPU执行卷积等算子。增加DSP线程或复用Python进程有有限收益，图转换及量化值得单独比较。板端频率策略仍为rknpu_ondemand，未修改主机调频或温控配置。
- VPU临时参数对照使用原MKV的只读测试副本，从0秒转为相同640×426 H.264 HLS、选取同一AAC音轨。原音轨重编码耗时42.148秒，AAC复制耗时24.406秒，下降约42.1%；两者均为rkmpp-rga硬解/零拷贝/硬编，无CPU视频回退。每组视频/音频各129片，本机完整双流解码退出0，输出时长均256.122410秒、音频48kHz双声道。这里只对比执行参数，尚未修改产品音频协商及码率约束。
- 反向顺序复测：先AAC复制24.840秒，再AAC重编码30.279秒，下降约18.0%。两轮原链路有波动，因此收益记录为本素材约18%–42%，不只报告最好一轮。原AAC与复制输出统一重封装为ADTS后均为6,278,223字节，SHA256均为`9920ee6b721b3cadc0eb44c99a8fc9087f12d5294da892979928cb57b7c0ac67`，确认音轨内容保留。
- INT8独立转换使用Toolkit2 2.3.2、Torch2.2.0、ONNX1.16.1、NumPy1.26.4、Protobuf4.25.4及SciPy1.13.1；与在线服务隔离。使用同一歌曲20/40/60/80秒处的频谱校准，首8秒留作对照。采用W8A8、normal算法、channel方法；转换成功，板端两次推理及两份完整8秒输出通过，无OOM、无应用后端降级。

| 同一8秒音频 | RKNN文件字节 | 分离阶段秒数 | 其中RKNN调用秒数 | 峰值进程RSS |
| --- | ---: | ---: | ---: | ---: |
| FP16基准 | 20,046,968 | 63.791 | 62.650 | 554.46MiB |
| INT8 W8A8 | 9,683,090 | 49.360 | 48.207 | 433.24MiB |
| INT4 W4A16 | 未生成 | 不支持 | 不支持 | 未测 |

- 表中分离阶段包括频谱处理、SDK推理和写WAV，不含依赖导入及模型初始化；INT8阶段耗时下降22.62%。只测8秒，不据此声称整曲INT8耗时或实时能力。模型SHA256为`ff6d560ec05c2fd66cfc936465e5ccc74bf09e368c01708cab509cb274dd55f3`，保留于NAS `test-output/npu-converter-work/int8-model/`，未安装到在线models卷。
- 留出的8秒相对ONNX CPU参考输出：INT8人声相对RMS误差33.95%、相关系数0.940760、SNR9.38dB；伴奏误差41.41%、相关系数0.923495、SNR7.66dB。重建输入误差约3.05e-5只说明两条输出相加还原原曲，不能用于证明分离质量。这版普通INT8量化未通过数值质量检查，不建议替换FP16；尚未做主观听辨或多歌曲质量验收。
- INT8转换后按用户要求尝试INT4，使用SDK公开的W4A16及group32配置、目标固定rk3566；SDK在config阶段拒绝，原文为`The quantized_dtype = 'w4a16' not support in 'rk3566'!`。没有生成INT4模型，也没有用CPU执行结果冒充INT4 NPU测速；结论限于当前板卡与SDK2.3.2组合。
- 对照WAV、逐阶段计时、量化校准频谱和构建/推理日志均保留在本次NAS测试目录。在线服务仍使用原模型及原安全配置；后续图改写、混合量化和更丰富校准集尚未验证，不能承诺收益。
- 收尾核对：反向VPU复测的每组261个HLS文件与首轮已解码文件逐一SHA256一致；两首媒体原件再次校验均OK。删除本轮9个临时容器并保存日志；Java/compute健康且重启数均0，主机设备权限保持原值，在线models卷仍为原FP16摘要。完整汇总保存于NAS `reports/optimization-20261108.json`；取回的8秒对照WAV摘要与NAS一致。本轮仅补充实测记录，`git diff --check`通过。
