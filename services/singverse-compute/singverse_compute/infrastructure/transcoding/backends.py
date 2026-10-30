"""Report build support separately from successful device execution."""
import platform
import threading
from .process import run
from ...core.errors import ComputeError

ENCODERS = {'software': 'libx264', 'qsv': 'h264_qsv', 'nvenc': 'h264_nvenc',
            'vaapi': 'h264_vaapi', 'amf': 'h264_amf', 'videotoolbox': 'h264_videotoolbox', 'rkmpp': 'h264_rkmpp'}

VIDEO_ENCODERS = {
    'h264': ENCODERS,
    'hevc': {'software':'libx265','qsv':'hevc_qsv','nvenc':'hevc_nvenc','vaapi':'hevc_vaapi',
             'amf':'hevc_amf','videotoolbox':'hevc_videotoolbox','rkmpp':'hevc_rkmpp'},
    'av1': {'software':'libsvtav1','qsv':'av1_qsv','nvenc':'av1_nvenc','vaapi':'av1_vaapi','amf':'av1_amf'},
}

class Backends:
    def __init__(self, executable, preferred='auto', device='/dev/dri/renderD128', memory_limit=1024**3):
        self.executable, self.preferred, self.device = executable, preferred, device
        self.memory_limit = memory_limit
        self._results = None
        self._features = None
        self._lock = threading.Lock()

    def prefix(self, name):
        if name == 'vaapi':
            return ['-vaapi_device', self.device]
        if name == 'qsv' and platform.system() == 'Linux':
            return ['-qsv_device', self.device]
        return []

    def upload(self, name):
        return ',format=nv12,hwupload' if name == 'vaapi' else ''

    def test(self, backend):
        import time
        started = time.monotonic()
        eligible = {'Linux':['qsv','vaapi','nvenc','rkmpp'], 'Windows':['qsv','nvenc','amf'], 'Darwin':['videotoolbox']}.get(platform.system(), []) + ['software']
        order = ['qsv','vaapi','nvenc','videotoolbox','amf','rkmpp','software'] if backend == 'auto' else [backend]
        for name in order:
            if name not in eligible:
                continue
            try:
                run([self.executable, '-v','error','-nostdin', *self.prefix(name), '-f','lavfi',
                     '-i','color=s=128x128:r=25:d=0.2', '-vf','format=nv12'+self.upload(name),
                     '-threads:v','2','-c:v',ENCODERS[name],'-frames:v','3','-f','null','-'],timeout=3, memory_limit=self.memory_limit)
                return {'passed':True, 'backend':name, 'hardware':name!='software',
                        'elapsedMs':round((time.monotonic()-started)*1000)}
            except ComputeError:
                pass
        return {'passed':False, 'backend':backend, 'hardware':False,
                'elapsedMs':round((time.monotonic()-started)*1000)}

    def inspect(self, refresh=False):
        with self._lock:
            if refresh:
                self._results = None
            if self._results is not None:
                return self._results
            try:
                listing = run([self.executable, '-hide_banner', '-encoders'], memory_limit=self.memory_limit).decode()
            except ComputeError:
                listing = ''
            system = platform.system()
            eligible = {'Linux': ['qsv','vaapi','nvenc','rkmpp'], 'Windows': ['qsv','nvenc','amf'],
                        'Darwin': ['videotoolbox']}.get(system, []) + ['software']
            results = []
            for name, encoder in ENCODERS.items():
                codecs = {}
                for codec, encoders in VIDEO_ENCODERS.items():
                    implementation = encoders.get(name, '')
                    installed = bool(implementation and implementation in listing and name in eligible)
                    passed = False
                    if installed:
                        try:
                            extra = ['-preset','8'] if implementation == 'libsvtav1' else []
                            run([self.executable, '-v','error', '-nostdin', *self.prefix(name), '-f','lavfi',
                                 '-i','color=s=128x128:r=25:d=0.2', '-vf','format=nv12' + self.upload(name),
                                 '-threads:v','2','-c:v',implementation, *extra, '-frames:v','3', '-f','null','-'], timeout=3, memory_limit=self.memory_limit)
                            passed = True
                        except ComputeError:
                            pass
                    codecs[codec] = {'installed':installed, 'samplePassed':passed, 'encoder':implementation}
                primary = codecs['h264']
                results.append({'backend': name, 'installed': primary['installed'], 'probeAvailable': any(c['samplePassed'] for c in codecs.values()),
                                'samplePassed': primary['samplePassed'], 'encoder': encoder, 'codecs':codecs,
                                'decodeAcceleration': False, 'toneMappingAcceleration': False})
            self._results = results
            return results

    def invalidate(self):
        with self._lock:
            self._results = None

    def features(self):
        if self._features is None:
            try:
                listing = run([self.executable, '-hide_banner', '-filters'], memory_limit=self.memory_limit).decode()
            except ComputeError:
                listing = ''
            names = {line.split()[1] for line in listing.splitlines() if len(line.split()) > 2}
            self._features = {name:name in names for name in ('subtitles','overlay','zscale','tonemap','tonemapx','scale_cuda','tonemap_cuda','yadif_cuda',
                'scale_vt','tonemap_videotoolbox','bwdif_videotoolbox','scale_vaapi','tonemap_vaapi',
                'deinterlace_vaapi','vpp_qsv','tonemap_opencl','scale_d3d11','scale_rkrga','hwmap')}
        return self._features

    def select(self, codec='h264'):
        results = self.inspect()
        order = [self.preferred] if self.preferred != 'auto' else ['qsv','vaapi','nvenc','videotoolbox','amf','rkmpp']
        for name in order + ['software']:
            if any(r['backend'] == name and r['codecs'].get(codec, {}).get('samplePassed') for r in results):
                return name
        raise ComputeError(503, 'No verified encoder available')
