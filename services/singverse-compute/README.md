# SingVerse Python计算节点

Python 3.12+、FastAPI 0.121.3、Uvicorn 0.38.0。承接后续服务端媒体探测、转解码、AI伴奏分析等重计算；当前仅可运行的健康与能力探测骨架，不安装媒体工具、GPU运行时或模型，不执行任务。

## 开发与验证

仓库根目录：

```sh
make compute-setup PYTHON=python3.12
make compute-check
make backend-check # 两种运行时的整体测试；需要JDK21
make compute-run
curl --fail http://127.0.0.1:8090/health
curl --fail http://127.0.0.1:8090/internal/v1/capabilities
```

如果`python3`已为3.12+，可以省略`PYTHON`参数。依赖仅安装到本目录`.venv`；`requirements.lock`固定全部直接/间接依赖及构建后端，安装项目时不再单独解析。首次安装需要访问包仓库。`make verify`前先完成setup，不更改系统Python。

自定义端口：在本目录执行`.venv/bin/python -m singverse_compute --port 8091`。默认只监听`127.0.0.1:8090`，单个ASGI进程；当前没有CPU/GPU性能承诺。`make compute-check`在随机回环端口启动真实Uvicorn，检查HTTP响应、错误状态和已提交OpenAPI与服务一致，不依赖数据库或真实媒体。

Java用`COMPUTE_NODE_URL=http://127.0.0.1:8090`配置内部客户端，默认空值关闭探测。两进程都不自动加载根`.env`。Java按需探测，不在启动或Actuator检查时请求；当前没有公开节点管理接口。

`make backend-check`统一执行Python检查和Java `backend-check` Maven Profile：普通Java测试、打包与真实进程集成测试。`ComputeIntegrationIT`由Java启动/停止本节点，验证内部协议与故障隔离；计算服务未运行、上线与退出时，主服务的Actuator/H5均保持可用。测试使用临时数据库和随机回环端口，不接触开发媒体或数据库；已纳入CI。节点连接不对应独立产品菜单，转换、伴奏分析和诊断融入原有业务流程。

## 已实现接口

| 路径 | 返回与边界 |
|---|---|
| `GET /health` | `{"status":"UP"}`，仅证明进程可应答 |
| `GET /internal/v1/capabilities` | `{"service":"singverse-compute","apiVersion":"1","capabilities":[]}`，没有可执行计算能力 |
| `GET /openapi.json` | OpenAPI 3.1内部探测契约；文档UI关闭 |

未知路径/不支持方法用404/405及Problem Details。没有占位转码/任务接口。更新内部探测契约时，在本目录重新生成并执行测试：

```sh
.venv/bin/python -c 'import json; from pathlib import Path; from singverse_compute.app import app; Path("../../contracts/openapi/compute-internal.openapi.json").write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")'
```

## 计算和业务边界

Java保留授权、曲库/队列/会话、任务编排及唯一业务数据写入。Python接收受控任务，产出衍生媒体或分析建议，不直接访问业务数据库。客户端只访问Java；设备本机解码、麦克风实时采集、监听及手机录音仍在TV/App本地。

后续真实任务需定义幂等、状态、超时、取消/重启恢复、资源限额及结果校验；重计算通过受限子进程/工具/模型运行时执行，保持HTTP事件循环可应答。媒体原件只读，兼容副本、模型缓存和分析结果独立保存。AI伴奏分析不等于伴奏生成/分离效果已验证，不能自动覆盖人工标注。具体任务、算法和硬件在实现时确认。

当前没有认证、应用Docker镜像或跨主机部署；独立主机/容器组网前补齐认证、TLS/受限网络与受控输入输出，核查GPU驱动和工具链。参考[整体架构](../../docs/architecture.md)。框架启动方式参考[FastAPI官方部署说明](https://fastapi.tiangolo.com/deployment/manually/)，ASGI骨架不代表重计算性能已验证。
