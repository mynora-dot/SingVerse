#!/usr/bin/env python3
"""Package a credential-free CUDA source deployment with normalized permissions."""
import argparse
import hashlib
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def contents():
    files = {'docker-compose.yaml': (ROOT/'compose.compute.nvidia.yaml').read_bytes(),
             '.dockerignore': (ROOT/'.dockerignore').read_bytes()}
    compute = ROOT/'services/singverse-compute'
    for path in sorted((compute/'singverse_compute').rglob('*')):
        if path.is_file() and not path.is_symlink() and path.suffix in ('.py', '.json', '.txt') and '__pycache__' not in path.parts:
            files[str(path.relative_to(ROOT))] = path.read_bytes()
    for path in sorted(compute.glob('requirements*.lock')):
        files[str(path.relative_to(ROOT))] = path.read_bytes()
    for path in [compute/'Dockerfile', *sorted((compute/'docker').glob('*'))]:
        if path.is_file() and not path.is_symlink():
            files[str(path.relative_to(ROOT))] = path.read_bytes()
    files['部署说明.md'] = '''# NVIDIA RTX 3070 / A3000 独立执行节点

仅适用 Linux x86_64，先安装兼容 NVIDIA 驱动与 NVIDIA Container Toolkit。
此包为源码构建包；首次构建联网下载依赖，不含模型、媒体或凭据。

1. 升级前在业务管理页停止提交新任务，等待或取消在途任务。
2. 业务节点 Java／前端需同步升级到支持 callbackOrigin 的版本；仅更新本包不够。
3. 上传解压，进入 SingVerse-NVIDIA 目录。所有参数在 docker-compose.yaml 中，无需 .env。
   默认监听 10.100.1.12:18090，该 IP 必须属于 GPU 宿主，并限制入站仅业务主机可访问。
   无需 media 目录、曲库挂载或业务地址配置；保留现有模型、缓存和认证命名卷。
4. 执行：

   docker compose -f docker-compose.yaml up -d --build
   docker compose -f docker-compose.yaml ps -a
   docker compose -f docker-compose.yaml logs --tail 80

在业务管理页配置 http://10.100.1.12:18090 和已有连接密钥，重新测试连接。
连接测试包含计算节点反向认证访问业务入口，业务主机需允许该访问。
独立部署时通过计算节点可达的业务 URL 打开管理页；Java 据此随任务发送回源地址。
如业务有多个入口／反向代理，业务部署可配置 SINGVERSE_CALLBACK_ORIGIN；该配置不放在性能节点。
Java 重启后无固定回源配置时，先访问管理页以重新确认入口，再运行后台任务。

本地曲库仅挂载到业务节点；WebDAV 凭据也只保存在业务节点。
性能节点通过任务 UUID、Bearer、固定内部接口及 HTTP Range 读取媒体，不接受本地文件路径。
下载／导入分离模型后重新执行 CUDA 试跑；视频 NVENC 与音频 CUDA 分别验收。

停止：docker compose -f docker-compose.yaml down
不要加 -v；项目名 singverse-compute-node 与原独立节点一致，不能同时启动两份。

本包不代表 A3000／3070 的真实歌曲、长曲与音质已验收；以现场复验为准。
'''.encode()
    files['SHA256SUMS'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(files.items())).encode()
    return files


def write_bundle(output, files):
    prefix = 'SingVerse-NVIDIA/'
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as bundle:
        directories = {prefix.rstrip('/')}
        for name in files:
            directories.update(str(p) for p in (Path(prefix)/name).parents if str(p) != '.')
        for name in sorted(directories):
            info = zipfile.ZipInfo(name+'/'); info.create_system = 3
            info.external_attr = (0o40755 << 16) | 0x10
            bundle.writestr(info, b'')
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(prefix+name); info.create_system = 3
            info.external_attr = 0o100644 << 16; info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_bundle(args.output, contents())
    print(f'{args.output}: {args.output.stat().st_size} bytes')


if __name__ == '__main__':
    main()
