"""Technical probe with restricted protocols and stable source-stream identifiers."""
import json
from ...core.errors import ComputeError
from ...domain.transcoding import Media, Stream
from .process import run

FORMATS = 'mp3,flac,ogg,wav,mov,matroska,webm,avi,asf,mpeg,mpegts,aac,ape,aiff'

class MediaProbe:
    def __init__(self, executable, memory_limit=1024**3):
        self.executable = executable
        self.memory_limit = memory_limit

    def _has_dovi_rpu(self, path, network, index):
        raw=run([self.executable,'-v','error','-protocol_whitelist','file,http,tcp' if network else 'file',
                 '-format_whitelist',FORMATS,'-probesize','8388608','-analyzeduration','5000000',
                 '-read_intervals','%+#8','-select_streams',str(index),'-show_frames',
                 '-show_entries','frame=pts_time:frame_side_data=side_data_type','-of','json',str(path)],limit=262144,memory_limit=self.memory_limit)
        frames=json.loads(raw).get('frames',[])
        return any('Dolby Vision RPU' in side.get('side_data_type','') for frame in frames for side in frame.get('side_data_list',[]))

    def read(self, path, network=False):
        raw = run([self.executable, '-v', 'error', '-protocol_whitelist',
                   'file,http,tcp' if network else 'file', '-format_whitelist', FORMATS,
                   '-probesize', '8388608', '-analyzeduration', '5000000',
                   '-show_format', '-show_streams', '-of', 'json', str(path)], memory_limit=self.memory_limit)
        try:
            data = json.loads(raw)
            fmt = data['format']
            container = fmt.get('format_name', '').split(',')[0]
            container = {'mov': 'mp4', 'matroska': 'mkv', 'mpegts': 'ts'}.get(container, container)
            streams = []
            for s in data.get('streams', [])[:64]:
                if s.get('codec_type') not in ('video', 'audio', 'subtitle') or s.get('disposition', {}).get('attached_pic'):
                    continue
                rate = s.get('avg_frame_rate', '0/1').split('/')
                fps = float(rate[0]) / max(1, float(rate[1])) if len(rate) == 2 else 0
                transfer = s.get('color_transfer', '')
                hdr = 'HDR10' if transfer == 'smpte2084' else 'HLG' if transfer == 'arib-std-b67' else ''
                dovi=next((d for d in s.get('side_data_list', []) if 'DOVI' in d.get('side_data_type','')), {})
                rotation=next((int(d.get('rotation',0)) for d in s.get('side_data_list', []) if 'Display Matrix' in d.get('side_data_type','')),0)
                if any('DOVI' in d.get('side_data_type', '') for d in s.get('side_data_list', [])):
                    hdr = 'DOVI'
                index = int(s['index'])
                streams.append(Stream(f'stream:{index}', index, s['codec_type'], s.get('codec_name', ''),
                    s.get('profile', ''), int(s.get('level', 0)), int(s.get('width', 0)), int(s.get('height', 0)), fps,
                    int(s.get('bits_per_raw_sample') or (10 if '10' in s.get('pix_fmt', '') else 8)),
                    int(s.get('channels', 0)), s.get('tags', {}).get('language', 'und'),
                    s.get('tags', {}).get('title', '')[:120], hdr, int(dovi.get('dv_profile',0)),
                    int(dovi.get('dv_bl_signal_compatibility_id',0)),bool(dovi.get('rpu_present_flag',0)) and self._has_dovi_rpu(path, network, index),
                    bool(dovi.get('el_present_flag',0)),s.get('field_order','unknown') not in ('unknown','progressive'),rotation))
            if not streams:
                raise ValueError()
            return Media(container, float(fmt.get('duration', 0)), int(fmt.get('bit_rate', 0)), tuple(streams))
        except (ValueError, KeyError, TypeError) as error:
            raise ComputeError(422, 'Invalid media probe result') from error
