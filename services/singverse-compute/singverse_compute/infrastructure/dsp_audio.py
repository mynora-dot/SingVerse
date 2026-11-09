"""FFmpeg-only accompaniment: bounded memory and no NumPy, Torch or GPU dependency."""
import math
import re
from ..domain.audio_separation import PAN
from ..core.errors import ComputeError
from .transcoding.process import run


def process(source, target, *, ffmpeg='ffmpeg', stream=0, channel=None, cancel=None,
            memory_limit=256*1024**2, output_format='aac', start=0, seconds=None, network=False,
            center_cancel=True, peak_gain=1.0):
    stats = {}
    def observe(line):
        match = re.search(r'astats@(input|output).*? (RMS level dB|Peak level dB|Number of NaNs|Number of Infs): ([^ ]+)', line)
        if match:
            stats.setdefault(match[1], {})[match[2]] = float(match[3])
    command = [ffmpeg, '-nostdin', '-hide_banner', '-nostats', '-v', 'info', '-xerror',
               '-protocol_whitelist', 'file,http,tcp' if network else 'file']
    if start:
        command.extend(['-ss', str(start)])
    command.extend(['-i', str(source), '-map', f'0:{stream}', '-vn'])
    if seconds is not None:
        command.extend(['-t', str(seconds)])
    measure = 'metadata=0:reset=0:measure_perchannel=none:measure_overall=RMS_level+Peak_level+Number_of_NaNs+Number_of_Infs'
    filters = ['aresample=44100:async=1:first_pts=0', 'astats@input=' + measure]
    if channel:
        index = 0 if channel == 'LEFT' else 1
        filters.append(f'pan=stereo|c0=c{index}|c1=c{index}')
    elif center_cancel:
        filters.append(PAN)
    if peak_gain != 1:
        filters.append(f'volume={peak_gain:.12g}')
    filters.append('astats@output=' + measure)
    command.extend(['-af', ','.join(filters), '-ac', '2', '-ar', '44100'])
    if output_format == 'flac':
        command.extend(['-c:a', 'flac', '-sample_fmt', 's32', '-bits_per_raw_sample', '24', '-compression_level', '0', '-f', 'flac'])
    else:
        command.extend(['-c:a', 'aac', '-b:a', '192k', '-f', 'ipod'])
    command.extend(['-y', str(target)])
    run(command, timeout=7200, limit=32768, cancel=cancel, memory_limit=memory_limit, error_line=observe)
    for side in ('input', 'output'):
        measured = stats.get(side, {})
        if not measured or any(measured.get(key, 0) > 0 for key in ('Number of NaNs', 'Number of Infs')):
            raise ComputeError(422, 'Invalid accompaniment signal statistics')
    original, backing = stats['input']['RMS level dB'], stats['output']['RMS level dB']
    if center_cancel and (not math.isfinite(backing) or backing < original - 40):
        raise ComputeError(422, 'DSP_NOT_SUITABLE：左右声道相同或伴奏接近静音，请选择干净档')
    # JSON does not permit +/- infinity for silence. Keep the condition explicit.
    return {name: {key: value if math.isfinite(value) else None for key, value in values.items()}
            for name, values in stats.items()}
