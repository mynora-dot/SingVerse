# 业务前端模型列表修复（Linux AMD64）

修复 NVIDIA、AMD、Apple 或 CPU 节点误显示 RKNN 模型的问题。
模型按适配后端和已检测设备筛选；RKNN 仅显示在有对应设备的节点，
并明确只支持节点本地部署，不提供网页下载或 ZIP 导入，不再显示 XML/BIN 提示。
NVIDIA A3000 使用标准分离模型与 CUDA，模型试跑状态仍以实际节点结果为准。

这是现有业务离线部署的前端更新包，只包含 `singverse-frontend:offline-amd64` 镜像。
不会更新 Java、数据库或计算节点；原 Compose、数据卷、密钥、模型与曲库继续保留。

1. 上传并解压本包，在解压目录执行：

```sh
sha256sum -c SHA256SUMS
sudo docker load -i images.tar
```

2. 切换到原业务目录，重建前端容器以使用刚导入的镜像（仅 restart 不会更换镜像）：

```sh
cd /vol1/1000/docker/SingVerse-Business
sudo docker compose up -d --no-deps --no-build --pull never --force-recreate singverse-frontend
sudo docker compose ps
```

3. 浏览器强制刷新 `http://10.100.1.12:18080/` 后进入性能节点配置。
   如原 Compose 使用其他前端镜像标签，应先将其改为 `singverse-frontend:offline-amd64`。

维护者打包命令：先构建上述 AMD64 前端镜像，再执行
`python3 scripts/package-app-offline.py --frontend-only --output /tmp/SingVerse-Frontend-Update.zip`。
