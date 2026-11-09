"""Isolated MDX forward runtimes. CPU execution imports neither Torch nor GPU drivers.

Only a release-owned, hash-verified ONNX graph is accepted. All frequency transforms
and file IO are shared across providers, so failure recovery can replay on CPU.
"""
import argparse
import hashlib
from importlib.metadata import PackageNotFoundError, version
import json
import os
from pathlib import Path
import platform
import sys
import time

from ..domain.audio_separation import MDX_BYTES, MDX_SHA256, PROVIDERS


def probe(cpu_only=False):
    result = {'system': platform.system(), 'osRelease': platform.release(), 'architecture': platform.machine(),
              'python': platform.python_version(), 'executable': sys.executable, 'cpuOnly': cpu_only, 'versions': {}, 'backends': []}
    import subprocess
    result['processor'] = platform.processor()
    try:
        if platform.system() == 'Darwin':
            result['processor'] = subprocess.run(['sysctl', '-n', 'machdep.cpu.brand_string'], capture_output=True, text=True, timeout=2, check=True).stdout.strip()
        elif platform.system() == 'Linux':
            result['cpuFeatures'] = '\n'.join(line for line in Path('/proc/cpuinfo').read_text().splitlines()
                if line.lower().startswith(('model name', 'flags', 'features', 'cpu implementer', 'cpu part')))[:8192]
    except (OSError, subprocess.SubprocessError):
        pass
    for package in ('numpy', 'soundfile', 'onnxruntime', 'onnxruntime-gpu', 'onnxruntime-directml',
                    'openvino', 'rknn-toolkit-lite2', 'mlx', 'mlx-audio-separator', 'onnx',
                    'tensorrt-cu12', 'tensorrt-cu12-bindings', 'tensorrt-cu12-libs'):
        try:
            result['versions'][package] = version(package)
        except PackageNotFoundError:
            pass
    try:
        import onnxruntime as ort
        providers = ort.get_available_providers()
        result['providers'] = providers
        result['backends'].extend(key for key, value in PROVIDERS.items() if value in providers and (not cpu_only or key == 'cpu'))
        # A GPU ORT wheel is not the independent CPU installation promised by the API.
        result['standaloneCpu'] = ('onnxruntime' in result['versions'] and
                                  not any(name in result['versions'] for name in ('onnxruntime-gpu', 'onnxruntime-directml')) and
                                  not set(providers).intersection({'CUDAExecutionProvider', 'TensorrtExecutionProvider', 'MIGraphXExecutionProvider', 'DmlExecutionProvider'}))
    except Exception:
        result['standaloneCpu'] = False
    try:
        import openvino as ov
        core = ov.Core()
        devices = ['CPU'] if cpu_only else core.available_devices
        result['openvinoDevices'] = {name: str(core.get_property(name, 'FULL_DEVICE_NAME')) for name in devices}
        result['openvinoDriver'] = {}
        for name in devices:
            for property_name in ('DRIVER_VERSION', 'DEVICE_UUID'):
                try:
                    result['openvinoDriver'][name+':'+property_name] = str(core.get_property(name, property_name))
                except Exception:
                    pass
        if 'CPU' in devices:
            result['backends'].append('openvino-cpu')
        if any(name.startswith('GPU') for name in devices):
            result['backends'].append('openvino')
    except Exception:
        pass
    if not cpu_only and 'rknn-toolkit-lite2' in result['versions']:
        from .hardware import device_tree
        compatible = device_tree('compatible')
        if any(name in compatible for name in ('rk3566', 'rk3568', 'rk3588')):
            result['backends'].append('rknn')
            result['board'] = compatible
    if not cpu_only and result['system'] == 'Darwin' and result['architecture'] == 'arm64' and result['versions'].get('mlx-audio-separator') == '0.1.20':
        import mlx.core as mx
        if mx.metal.is_available():
            result['backends'].append('mlx')
            result.setdefault('driver', {})['metal'] = str(mx.device_info())
    # Driver identities invalidate qualification; no context creation on an API request.
    result.setdefault('driver', {})
    try:
        if not cpu_only:
            result['driver']['gpu'] = subprocess.run(['nvidia-smi', '--query-gpu=uuid,name,driver_version', '--format=csv,noheader'],
                capture_output=True, text=True, timeout=2, check=True).stdout[:4096]
    except (OSError, subprocess.SubprocessError):
        pass
    for name, path in [('nvidia', '/proc/driver/nvidia/version'), ('kernel', '/proc/version')]:
        if cpu_only and name == 'nvidia':
            continue
        try:
            result['driver'][name] = Path(path).read_text()[:2048]
        except OSError:
            pass
    if not cpu_only:
        for path in ('/opt/rocm/.info/version', '/opt/rocm/.info/version-dev',
                     '/sys/module/amdgpu/srcversion', '/sys/module/i915/srcversion', '/sys/module/rknpu/srcversion'):
            try:
                result['driver'][path] = Path(path).read_text().strip()[:2048]
            except OSError:
                pass
        result['driver']['pci'] = []
        for card in sorted(Path('/sys/class/drm').glob('card[0-9]*/device')):
            try:
                result['driver']['pci'].append({name: (card/name).read_text().strip() for name in ('vendor', 'device', 'revision')})
            except OSError:
                pass
    result['fingerprint'] = hashlib.sha256(json.dumps(result, sort_keys=True).encode()).hexdigest()
    return result


class Runtime:
    def __init__(self, model, backend, cache, threads=1, rknn_model=None):
        with Path(model).open('rb') as stream:
            if os.fstat(stream.fileno()).st_size != MDX_BYTES or hashlib.file_digest(stream, 'sha256').hexdigest() != MDX_SHA256:
                raise ValueError('Expected pinned UVR MDXNET 9482 model')
        self.backend, self.loaded = backend, False
        self.placement, self.inference_ms, self.forwards = {}, 0., 0
        self.transfers = {'hostToDevice': None, 'deviceExecution': None, 'deviceToHost': None}
        self.cache = Path(cache)
        self.cache.mkdir(parents=True, exist_ok=True)
        if backend == 'mlx':
            import mlx.core as mx
            import numpy as np
            from mlx_audio_separator.separator.models.mdx.loader import load_mdx_model
            # Use the same frozen MDX graph recipe; no package STFT or postprocessing.
            mx.set_default_device(mx.gpu)
            self.model, _ = load_mdx_model(str(model), {'mdx_dim_f_set': 2048,
                'mdx_dim_t_set': 8, 'mdx_n_fft_scale_set': 4096, 'mdx_hop_length': 1024})
            mx.eval(self.model.parameters())
            self.transfers = dict.fromkeys(self.transfers, 0.)
            def forward(value):
                started = time.monotonic()
                tensor = mx.array(value, dtype=mx.float32)
                mx.eval(tensor)
                self.transfers['hostToDevice'] += (time.monotonic()-started)*1000
                started = time.monotonic()
                output = self.model(tensor)
                mx.eval(output)
                self.transfers['deviceExecution'] += (time.monotonic()-started)*1000
                started = time.monotonic()
                result = np.array(output, dtype=np.float32)
                self.transfers['deviceToHost'] += (time.monotonic()-started)*1000
                return result
            self.forward = forward
            self.placement = {'MLX': 'Metal GPU; NumPy CPU spectral DSP'}
        elif backend == 'rknn':
            from .rknn_audio import Forward
            if not rknn_model:
                raise ValueError('RKNN converted model directory is required')
            self.forward = Forward(rknn_model)
            self.placement = {'RKNN': 'SDK graph; may contain CPU operators, CPU spectral DSP'}
        elif backend in ('openvino', 'openvino-cpu'):
            import openvino as ov
            core = ov.Core()
            target = 'CPU' if backend == 'openvino-cpu' else next((x for x in core.available_devices if x.startswith('GPU')), None)
            if target is None:
                raise RuntimeError('OpenVINO GPU is unavailable')
            config = {'CACHE_DIR': str(self.cache), 'PERFORMANCE_HINT': 'LATENCY'}
            if target == 'CPU':
                config['INFERENCE_NUM_THREADS'] = threads
            graph = core.read_model(str(model))
            graph.reshape({graph.input(0): [1, 4, 2048, 256]})
            self.session = core.compile_model(graph, target, config)
            actual = list(self.session.get_property('EXECUTION_DEVICES'))
            if not actual or any(not name.startswith(target.split('.')[0]) for name in actual):
                raise RuntimeError('OpenVINO used an unexpected inference device')
            self.request = self.session.create_infer_request()
            self.placement = {'OpenVINO': actual}
            self.forward = lambda value: self.request.infer({self.session.input(0): value})[self.session.output(0)].copy()
        else:
            if backend in ('cuda', 'tensorrt'):
                # ORT 1.20 predates preload_dlls. Load only this worker's installed
                # CUDA/cuDNN wheels; the independent CPU worker never enters here.
                import ctypes
                import site
                for library in ('libnvJitLink.so.12', 'libcudart.so.12', 'libcublasLt.so.12', 'libcublas.so.12', 'libcufft.so.11', 'libcudnn.so.9'):
                    matches = [p for folder in site.getsitepackages() for p in Path(folder).glob('nvidia/*/lib/'+library)]
                    if matches:
                        ctypes.CDLL(str(matches[0]), mode=ctypes.RTLD_GLOBAL)
                if backend == 'tensorrt':
                    for library in ('libnvinfer.so.10', 'libnvinfer_plugin.so.10', 'libnvonnxparser.so.10'):
                        matches = [p for folder in site.getsitepackages() for directory in ('tensorrt_libs', 'tensorrt_cu12_libs')
                                   for p in (Path(folder)/directory).glob(library)]
                        if matches:
                            ctypes.CDLL(str(matches[0]), mode=ctypes.RTLD_GLOBAL)
                    import tensorrt
                    if not tensorrt.__version__.startswith('10.4.'):
                        raise RuntimeError('ORT 1.20 requires the pinned TensorRT 10.4 compatibility baseline')
            import onnxruntime as ort
            if backend not in PROVIDERS or PROVIDERS[backend] not in ort.get_available_providers():
                raise RuntimeError('Requested ONNX execution provider is unavailable')
            options = ort.SessionOptions()
            options.intra_op_num_threads = threads
            options.inter_op_num_threads = 1
            options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
            options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            options.enable_profiling = True
            if backend == 'coreml':
                options.add_free_dimension_override_by_name('batch_size', 1)
            options.profile_file_prefix = str(self.cache / 'placement')
            if backend == 'directml':
                options.enable_mem_pattern = False
            provider = PROVIDERS[backend]
            settings = {}
            if backend == 'tensorrt':
                settings = {'trt_fp16_enable': True, 'trt_engine_cache_enable': True,
                            'trt_engine_cache_path': str(self.cache), 'trt_timing_cache_enable': True,
                            'trt_timing_cache_path': str(self.cache)}
            elif backend == 'coreml':
                # Legacy flags work with the repository's pinned ORT 1.20.1.
                settings = {'coreml_flags': '0'}
            providers = [(provider, settings)]
            if backend == 'tensorrt' and 'CUDAExecutionProvider' in ort.get_available_providers():
                providers.append('CUDAExecutionProvider')
            if backend != 'cpu':
                providers.append('CPUExecutionProvider')
            self.session = ort.InferenceSession(str(model), sess_options=options, providers=providers)
            self.session.disable_fallback()
            if provider not in self.session.get_providers():
                raise RuntimeError('Requested ONNX provider failed to initialize')
            self.input = self.session.get_inputs()[0].name
            if len(self.session.get_inputs()) != 1 or len(self.session.get_outputs()) != 1:
                raise ValueError('Unexpected MDX graph interface')
            for port in [*self.session.get_inputs(), *self.session.get_outputs()]:
                if port.type != 'tensor(float)' or list(port.shape)[1:] != [4, 2048, 256]:
                    raise ValueError('Unexpected MDX graph shape or type')
            if backend in ('cuda', 'tensorrt'):
                # Reuse host/device buffers over every tile rather than allocating per forward.
                import numpy as np
                self.binding = self.session.io_binding()
                self.device_input = ort.OrtValue.ortvalue_from_shape_and_type([1, 4, 2048, 256], np.float32, 'cuda', 0)
                self.device_output = ort.OrtValue.ortvalue_from_shape_and_type([1, 4, 2048, 256], np.float32, 'cuda', 0)
                self.binding.bind_ortvalue_input(self.input, self.device_input)
                self.binding.bind_ortvalue_output(self.session.get_outputs()[0].name, self.device_output)
                self.transfers = dict.fromkeys(self.transfers, 0.)
                def forward(value):
                    started = time.monotonic()
                    self.device_input.update_inplace(value)
                    self.binding.synchronize_inputs()
                    self.transfers['hostToDevice'] += (time.monotonic()-started)*1000
                    started = time.monotonic()
                    self.session.run_with_iobinding(self.binding)
                    self.binding.synchronize_outputs()
                    self.transfers['deviceExecution'] += (time.monotonic()-started)*1000
                    started = time.monotonic()
                    result = self.device_output.numpy()
                    self.transfers['deviceToHost'] += (time.monotonic()-started)*1000
                    return result
                self.forward = forward
            else:
                self.forward = lambda value: self.session.run(None, {self.input: value})[0]

    def infer(self, value):
        started = time.monotonic()
        try:
            return self.forward(value)
        except Exception as error:
            if self.backend not in ('cpu', 'openvino-cpu'):
                raise RuntimeError('Hardware accelerator execution failed: ' + str(error)[:300]) from error
            raise
        finally:
            self.inference_ms += (time.monotonic()-started)*1000
            self.forwards += 1

    def profile(self):
        if hasattr(self, 'input') and not self.placement:
            filename = Path(self.session.end_profiling())
            try:
                if filename.stat().st_size > 32*1024**2:
                    raise RuntimeError('Inference profile exceeded budget')
                for event in json.loads(filename.read_text()):
                    provider = event.get('args', {}).get('provider')
                    if provider:
                        self.placement[provider] = self.placement.get(provider, 0) + 1
            finally:
                filename.unlink(missing_ok=True)
            if not self.placement.get(PROVIDERS[self.backend]):
                raise RuntimeError('Requested provider did not execute any model nodes')
        return self.placement

    def separate(self, source, output, send, *, backing_only=True):
        from .uvr_audio import separate
        started = time.monotonic()
        before = self.inference_ms
        transfers_before = dict(self.transfers)
        metrics = {}
        folder = separate(source, output, self.infer, float_output=True, backing_only=backing_only, progress=send, metrics=metrics)
        result = {'backend': self.backend, 'modelReused': self.loaded,
                  'timingsMs': {'modelInference': round(self.inference_ms-before, 3),
                                'spectralPipeline': round((time.monotonic()-started)*1000, 3)},
                  'placement': self.profile(), 'cpuSpectralDsp': True,
                  'precision': ('fp16-preferred-mixed' if self.backend == 'tensorrt' else
                                'device-selected' if self.backend in ('rknn', 'openvino', 'openvino-cpu', 'coreml', 'directml') else 'fp32'),
                  'backing': str(folder/'no_vocals.wav'), **metrics}
        result['timingsMs'].update({key: round(value-transfers_before[key], 3) if value is not None else None
                                   for key, value in self.transfers.items()})
        self.loaded = True
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--probe', action='store_true')
    parser.add_argument('--cpu-only', action='store_true')
    parser.add_argument('--model')
    parser.add_argument('--backend', default='cpu')
    parser.add_argument('--cache')
    parser.add_argument('--threads', type=int, default=1)
    parser.add_argument('--rknn-model')
    args = parser.parse_args()
    if args.probe:
        print(json.dumps(probe(args.cpu_only)), flush=True)
        return
    runtime = None
    for line in sys.stdin:
        request = json.loads(line)
        identifier = request['id']
        def send(value):
            print(json.dumps({'id': identifier, **value}), flush=True)
        try:
            load = time.monotonic()
            reused = runtime is not None
            if runtime is None:
                try:
                    runtime = Runtime(args.model, args.backend, args.cache, args.threads, args.rknn_model)
                except Exception as error:
                    if args.backend not in ('cpu', 'openvino-cpu'):
                        raise RuntimeError('Hardware accelerator initialization failed: ' + str(error)[:300]) from error
                    raise
            loaded_ms = (time.monotonic()-load)*1000
            if request.get('op') != 'separate':
                raise ValueError('Unknown accompaniment operation')
            result = runtime.separate(request['source'], request['output'], send,
                                      backing_only=request.get('backingOnly', True))
            result['modelReused'] = reused
            result['timingsMs']['modelLoad'] = round(loaded_ms, 3)
            if request.get('compare'):
                import numpy as np
                import soundfile as sf
                actual, rate = sf.read(result['backing'], dtype='float32', always_2d=True)
                reference, other_rate = sf.read(request['compare'], dtype='float32', always_2d=True)
                if actual.shape != reference.shape or rate != other_rate:
                    raise ValueError('Provider comparison timeline mismatch')
                result['relativeError'] = float(np.linalg.norm(actual-reference)/max(float(np.linalg.norm(actual)), 1e-8))
            send({'ok': True, 'result': result})
        except Exception as error:
            send({'ok': False, 'error': str(error)[:512]})
            return


if __name__ == '__main__':
    main()
