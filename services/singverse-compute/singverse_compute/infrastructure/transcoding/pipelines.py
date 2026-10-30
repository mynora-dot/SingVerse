"""Fixed GPU graphs, admitted only after an actual-source decode/filter/encode trial."""
from dataclasses import dataclass
from fractions import Fraction
import threading
from ...core.errors import ComputeError
from .backends import VIDEO_ENCODERS
from .process import run
from .probe import FORMATS

@dataclass(frozen=True)
class VideoPipeline:
    name: str
    inputs: tuple[str, ...] = ()
    filters: tuple[str, ...] = ()
    hardware_decode: bool = False
    zero_copy: bool = False
    gpu_tone_map: bool = False
    dovi: bool = False
    fallback_reason: str = ''

    def view(self):
        return {'name':self.name, 'hardwareDecode':self.hardware_decode, 'zeroCopy':self.zero_copy,
                'gpuToneMapping':self.gpu_tone_map, 'dolbyVision':self.dovi, 'fallbackReason':self.fallback_reason}


def dimensions(video, target):
    width, height = video.width, video.height
    if abs(video.rotation) % 180 == 90:
        width, height = height, width
    if not width or not height:
        raise ComputeError(422, 'Invalid video dimensions')
    ratio = min(Fraction(target.width, width), Fraction(target.height, height), Fraction(1))
    return max(2, int(width*ratio)//2*2), max(2, int(height*ratio)//2*2)


def validate_dovi(video):
    if video.hdr == 'DOVI' and (video.codec != 'hevc' or video.bit_depth != 10 or video.dv_profile not in (5,8) or not video.dv_rpu or video.dv_el):
        raise ComputeError(422, 'Dolby Vision requires a single-layer profile 5/8 stream with RPU metadata')


def software_filters(video, target, features):
    validate_dovi(video)
    filters = []
    if video.interlaced:
        filters += ['bwdif=mode=send_frame:deint=interlaced']
    if video.hdr:
        if features.get('tonemapx'):
            filters += ['format=yuv420p10le', 'tonemapx=tonemap=bt2390:format=yuv420p:t=bt709:m=bt709:p=bt709:range=tv:apply_dovi=true']
        elif video.hdr == 'DOVI':
            raise ComputeError(503, 'Dolby Vision requires a metadata-aware tonemapper')
        elif features.get('zscale') and features.get('tonemap'):
            filters += ['zscale=t=linear:npl=100','format=gbrpf32le','tonemap=tonemap=mobius:desat=0','zscale=p=bt709:t=bt709:m=bt709:r=tv']
        else:
            raise ComputeError(503, 'Configured FFmpeg lacks HDR tone mapping')
    width,height = dimensions(video,target)
    return filters + [f'scale=w={width}:h={height}', f'fps={min(video.fps or target.fps,target.fps)}', 'format=yuv420p']


def candidates(backend, video, target, features):
    """No request-provided expression ever enters a graph. Unsupported stages fall back explicitly."""
    validate_dovi(video)
    if backend == 'software' or video.rotation or target.burn_subtitle:
        return []
    # Hardware APIs generally cannot decode AVC High 10; let native FFmpeg decode it.
    if video.codec == 'h264' and video.bit_depth > 8 and backend not in ('videotoolbox','rkmpp'):
        return []
    if video.codec not in ('h264','hevc','av1','vp9','vp8','mpeg2video','mpeg4','vc1'):
        return []
    width,height = dimensions(video,target)
    fps = f'fps={min(video.fps or target.fps,target.fps)}'
    hdr = bool(video.hdr)
    dv = video.hdr == 'DOVI'
    plans = []
    def add(name, inputs, filters, required, deinterlace=None):
        if any(not features.get(f) for f in required):
            return
        if video.interlaced:
            if not deinterlace or not features.get(deinterlace.split('=')[0]):
                return
            filters = [deinterlace] + filters
        plans.append(VideoPipeline(name,tuple(inputs),tuple([fps]+filters),True,True,hdr,dv))
    if backend == 'nvenc':
        inputs=['-hwaccel','cuda','-hwaccel_output_format','cuda']
        filters=[]
        if hdr:
            filters += ['tonemap_cuda=tonemap=bt2390:format=nv12:t=bt709:m=bt709:p=bt709:range=tv:apply_dovi=true']
        filters += [f'scale_cuda=w={width}:h={height}:format=nv12']
        add('cuda',inputs,filters,['scale_cuda']+(['tonemap_cuda'] if hdr else []),'yadif_cuda=mode=send_frame:deint=interlaced')
    elif backend == 'videotoolbox':
        inputs=['-hwaccel','videotoolbox','-hwaccel_output_format','videotoolbox_vld']
        filters=(['tonemap_videotoolbox=tonemap=bt2390:format=nv12:t=bt709:m=bt709:p=bt709:range=tv:apply_dovi=true'] if hdr else [])
        filters += [f'scale_vt=w={width}:h={height}:format=nv12']
        add('videotoolbox-metal',inputs,filters,['scale_vt']+(['tonemap_videotoolbox'] if hdr else []),'bwdif_videotoolbox=mode=send_frame:deint=interlaced')
    elif backend == 'vaapi':
        inputs=['-hwaccel','vaapi','-hwaccel_output_format','vaapi']
        scale=f'scale_vaapi=w={width}:h={height}:format=nv12'
        if not dv and video.hdr != 'HLG':
            filters=(['tonemap_vaapi=format=nv12:p=bt709:t=bt709:m=bt709'] if hdr else []) + [scale]
            add('vaapi-vpp',inputs,filters,['scale_vaapi']+(['tonemap_vaapi'] if hdr else []),'deinterlace_vaapi=mode=default:rate=frame:auto=1')
        if hdr:
            add('vaapi-opencl',inputs,['hwmap=derive_device=opencl',
                'tonemap_opencl=tonemap=bt2390:format=nv12:t=bt709:m=bt709:p=bt709:range=tv:apply_dovi=true',
                'hwmap=derive_device=vaapi:reverse=1','format=vaapi',scale],['tonemap_opencl','scale_vaapi'],
                'deinterlace_vaapi=mode=default:rate=frame:auto=1')
    elif backend == 'qsv' and video.codec in ('h264','hevc','av1','vp9','mpeg2video') and not dv:
        inputs=['-hwaccel','qsv','-hwaccel_output_format','qsv','-c:v',video.codec+'_qsv']
        # QSV VPP tonemapping is limited to PQ/HDR10; HLG requires a metadata-aware fallback.
        if video.hdr != 'HLG':
            vpp=f'vpp_qsv=w={width}:h={height}:format=nv12'+(':tonemap=1' if hdr else '')
            add('qsv-vpp',inputs,[vpp],['vpp_qsv'],'vpp_qsv=deinterlace=advanced')
    elif backend == 'amf':
        inputs=['-hwaccel','d3d11va','-hwaccel_output_format','d3d11']
        scale=f'scale_d3d11=w={width}:h={height}:format=nv12'
        if not hdr:
            add('amf-d3d11',inputs,[scale],['scale_d3d11'])
        else:
            add('amf-opencl',inputs,['hwmap=derive_device=opencl',
                'tonemap_opencl=tonemap=bt2390:format=nv12:t=bt709:m=bt709:p=bt709:range=tv:apply_dovi=true',
                'hwmap=derive_device=d3d11va:reverse=1','format=d3d11',scale],['tonemap_opencl','scale_d3d11'])
    elif backend == 'rkmpp':
        inputs=['-hwaccel','rkmpp','-hwaccel_output_format','drm_prime','-c:v',video.codec+'_rkmpp']
        scale=f'scale_rkrga=w={width}:h={height}:format=nv12'
        if not hdr:
            add('rkmpp-rga',inputs,[scale],['scale_rkrga'])
        else:
            add('rkmpp-opencl',inputs,['hwmap=derive_device=opencl',
                'tonemap_opencl=tonemap=bt2390:format=nv12:t=bt709:m=bt709:p=bt709:range=tv:apply_dovi=true',
                'hwmap=derive_device=rkmpp:reverse=1','format=drm_prime',scale],['tonemap_opencl','scale_rkrga'])
    return plans


def copyback_candidate(backend, video):
    if video.rotation or video.codec not in ('h264','hevc','av1','vp9','vp8','mpeg2video','mpeg4','vc1'):
        return None
    if video.codec == 'h264' and video.bit_depth > 8 and backend not in ('videotoolbox','rkmpp'):
        return None
    options={'videotoolbox':['-hwaccel','videotoolbox','-hwaccel_output_format','videotoolbox_vld'],
             'nvenc':['-hwaccel','cuda','-hwaccel_output_format','cuda'],
             'vaapi':['-hwaccel','vaapi','-hwaccel_output_format','vaapi'],
             'amf':['-hwaccel','d3d11va','-hwaccel_output_format','d3d11']}
    if backend=='qsv' and video.hdr!='DOVI' and video.codec in ('h264','hevc','av1','vp9','mpeg2video'):
        options[backend]=['-hwaccel','qsv','-hwaccel_output_format','qsv','-c:v',video.codec+'_qsv']
    if backend=='rkmpp':
        options[backend]=['-hwaccel','rkmpp','-hwaccel_output_format','drm_prime','-c:v',video.codec+'_rkmpp']
    if backend not in options:
        return None
    return VideoPipeline(backend+'-copyback',tuple(options[backend]),
        ('hwdownload','format=p010le' if video.bit_depth>8 else 'format=nv12'),True,False,False,
        video.hdr=='DOVI','CPU_FILTERS_REQUIRED')


class PipelineSelector:
    def __init__(self, executable, backends, memory_limit=1024**3):
        self.executable, self.backends = executable, backends
        self.memory_limit = memory_limit
        self._verified = {}
        self._lock = threading.Lock()

    def choose(self, source, network, media, target, backend, cancel):
        video=next((s for s in media.streams if s.kind=='video'),None)
        if not video or target.mode != 'TRANSCODE':
            return VideoPipeline('copy')
        features=self.backends.features()
        plans=candidates(backend,video,target,features)
        copyback=copyback_candidate(backend,video)
        if copyback:
            plans.append(copyback)
        for plan in plans:
            command=[self.executable,'-v','error','-nostdin',*self.backends.prefix(backend),
                     '-protocol_whitelist','file,http,tcp' if network else 'file','-format_whitelist',FORMATS,
                     '-probesize','8388608','-analyzeduration','5000000',*plan.inputs]
            if target.start:
                command += ['-ss',str(target.start)]
            filters=list(plan.filters)
            if not plan.zero_copy:
                filters += software_filters(video,target,features)
                filters[-1] += self.backends.upload(backend)
            command += ['-i',str(source),'-map',f'0:{video.index}','-an','-sn','-vf',','.join(filters),
                        '-threads:v','2','-c:v',VIDEO_ENCODERS[target.codec][backend],'-frames:v','8','-f','null','-']
            try:
                run(command,timeout=10,cancel=cancel,limit=262144,memory_limit=self.memory_limit)
                with self._lock:
                    self._verified[(backend,video.codec,video.bit_depth,video.hdr,video.dv_profile,target.codec)] = plan.view()
                return plan
            except ComputeError:
                if cancel.is_set():
                    raise
        return VideoPipeline('software-filters',dovi=video.hdr=='DOVI',fallback_reason='SOFTWARE_SELECTED' if backend=='software' else 'HARDWARE_TRIAL_UNAVAILABLE_OR_FAILED')

    def verified(self, backend):
        with self._lock:
            return [{'inputCodec':key[1],'bitDepth':key[2],'hdr':key[3],'dvProfile':key[4],'outputCodec':key[5],**value}
                    for key,value in self._verified.items() if key[0]==backend]
