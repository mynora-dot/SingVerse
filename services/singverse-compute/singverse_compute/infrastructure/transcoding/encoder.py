"""Fixed FFmpeg templates. No request can supply command or filter fragments."""
from dataclasses import asdict
from pathlib import Path
from .backends import VIDEO_ENCODERS
from .process import run
from .probe import FORMATS
from .pipelines import PipelineSelector, software_filters, dimensions
from ...core.errors import ComputeError

class Encoder:
    def __init__(self, executable, probe, backends, threads=2, memory_limit=1024**3):
        self.executable, self.probe, self.backends, self.threads = executable, probe, backends, threads
        self.memory_limit = memory_limit
        self.pipelines = PipelineSelector(executable, backends, memory_limit)

    def execute(self, source, network, media, target, directory, cancel, progress, backend):
        video = next((s for s in media.streams if s.kind == 'video'), None)
        audios = [s for s in media.streams if s.kind == 'audio']
        if target.audio_id:
            audios = [s for s in audios if s.id == target.audio_id]
            if not audios:
                raise ComputeError(422, 'Audio stream unavailable')
        subtitle = next((s for s in media.streams if s.kind == 'subtitle' and s.id == target.subtitle_id), None)
        if target.subtitle_id and not subtitle:
            raise ComputeError(422, 'Subtitle stream unavailable')
        prefix = [self.executable, '-v','error', '-nostdin', '-y', '-threads',str(self.threads),
                  '-filter_threads',str(self.threads), '-protocol_whitelist','file,http,tcp' if network else 'file',
                  '-format_whitelist',FORMATS]
        inputs = ['-ss',str(target.start)] if target.start else []
        inputs += ['-i',str(source)]
        text_subtitle = bool(subtitle and subtitle.codec in ('ass','ssa','subrip','webvtt','mov_text'))
        bitmap_subtitle = bool(subtitle and subtitle.codec in ('hdmv_pgs_subtitle','dvd_subtitle','dvb_subtitle','xsub'))
        if subtitle and not text_subtitle and not bitmap_subtitle:
            raise ComputeError(422, 'Subtitle format unavailable')
        if subtitle and text_subtitle:
            suffix, codec = ('ass','ass') if target.burn_subtitle else ('vtt','webvtt')
            run(prefix + inputs + ['-map',f'0:{subtitle.index}', '-c:s',codec,str(directory / ('subtitle.' + suffix))], cancel=cancel, memory_limit=self.memory_limit)
        if bitmap_subtitle and not target.burn_subtitle:
            raise ComputeError(422, 'Bitmap subtitles require burn-in')
        features = self.backends.features()
        if text_subtitle and target.burn_subtitle and not features['subtitles']:
            raise ComputeError(503, 'Configured FFmpeg lacks subtitle rendering')
        plan = self.pipelines.choose(source, network, media, target, backend, cancel)
        progress('pipeline', plan.view())
        filters = []
        if video and target.mode == 'TRANSCODE':
            if plan.zero_copy:
                filters = list(plan.filters)
            else:
                filters = list(plan.filters) + software_filters(video, target, features)
                if text_subtitle and target.burn_subtitle:
                    filters += ['subtitles=subtitle.ass']
                if not bitmap_subtitle:
                    filters[-1] += self.backends.upload(backend)
        command = prefix + self.backends.prefix(backend) + list(plan.inputs) + inputs
        complex_filter = bool(video and bitmap_subtitle and target.burn_subtitle)
        if video:
            command += ['-map','[video]' if complex_filter else f'0:{video.index}']
        for audio in audios:
            command += ['-map',f'0:{audio.index}']
        command += ['-map_metadata','-1', '-map_chapters','-1']
        if video:
            command += ['-threads:v',str(self.threads), '-c:v', VIDEO_ENCODERS[target.codec][backend] if target.mode == 'TRANSCODE' else 'copy']
            if filters:
                if complex_filter:
                    width,height=dimensions(video,target)
                    graph=f'[0:{video.index}]'+','.join(filters)+f'[main];[0:{subtitle.index}]scale=w={width}:h={height},format=yuva420p[sub];[main][sub]overlay=eof_action=pass:repeatlast=0'+self.backends.upload(backend)+'[video]'
                    command += ['-filter_complex',graph]
                else:
                    command += ['-vf',','.join(filters)]
                command += ['-b:v',str(target.bitrate), '-maxrate',str(target.bitrate),
                            '-bufsize',str(target.bitrate*2), '-force_key_frames','expr:gte(t,n_forced*4)']
                if backend == 'software':
                    command += ['-preset','8' if target.codec == 'av1' else 'veryfast']
                if target.codec == 'hevc':
                    command += ['-tag:v','hvc1']
        copy_audio = target.mode == 'REMUX'
        command += ['-c:a','copy' if copy_audio else 'aac']
        if not copy_audio:
            command += ['-b:a',str(target.audio_bitrate)]
            for i, audio in enumerate(audios):
                # Stereo L/R content is never collapsed; multichannel is limited explicitly.
                command += [f'-ac:a:{i}',str(min(audio.channels or 2, max(2, target.max_channels)))]
        for i, audio in enumerate(audios):
            command += [f'-metadata:s:a:{i}',f'language={audio.language}',
                        f'-metadata:s:a:{i}',f'title={audio.title or audio.id}']
        if video and video.hdr == 'DOVI' and target.mode != 'TRANSCODE':
            command += ['-strict','-2']
        command += ['-progress','pipe:1', '-stats_period','1']
        if target.codec == 'av1' and target.purpose == 'REALTIME' and not target.fmp4:
            raise ComputeError(422, 'AV1 HLS requires fragmented MP4')
        if target.purpose == 'OFFLINE':
            command += ['-movflags','+faststart', '-f','mp4',str(directory / 'output.partial.mp4')]
        else:
            streams = ['v:0,agroup:audio' if audios else 'v:0'] if video else []
            streams += [f'a:{i},agroup:audio,name:audio{i}' + (',default:yes' if i == 0 else '') for i in range(len(audios))]
            extension = 'm4s' if target.fmp4 else 'ts'
            command += ['-f','hls', '-hls_time','4', '-hls_list_size','0', '-hls_playlist_type','event',
                        '-hls_segment_type','fmp4' if target.fmp4 else 'mpegts',
                        '-hls_flags','temp_file', '-master_pl_name','master.m3u8',
                        '-var_stream_map',' '.join(streams), '-hls_segment_filename',str(directory / f'stream_%v_%06d.{extension}')]
            if video and video.hdr == 'DOVI' and target.mode != 'TRANSCODE':
                command += ['-hls_segment_options','strict=-2']
            if target.fmp4:
                command += ['-hls_fmp4_init_filename','init_%v.mp4' if len(streams) > 1 else 'init_0.mp4']
            command += [str(directory / 'stream_%v.m3u8')]
        def report(value):
            if '=' in value:
                key, val = value.split('=', 1)
                if key in ('out_time_us','speed','progress'):
                    progress(key, val)
        from ..hardware import accelerator_failure, non_accelerator_failure
        failures, blocked = [], []
        def observe_error(line):
            if non_accelerator_failure(line): blocked.append(True)
            if accelerator_failure(line): failures.append(line)
        try:
            run(command, timeout=max(60, min(21600, (media.duration or 3600)*4)), cancel=cancel,
            limit=8*1024*1024, line=report, cwd=directory, memory_limit=self.memory_limit, metrics=lambda peak: progress('memory',peak), error_line=observe_error)
        except ComputeError as error:
            if failures and not blocked and not non_accelerator_failure(error.message) and not cancel.is_set():
                raise ComputeError(422, 'Hardware accelerator execution failed') from error
            raise
        if target.purpose == 'OFFLINE':
            output = directory / 'output.partial.mp4'
            result = self.probe.read(output)
            if video and video.hdr and target.mode == 'TRANSCODE' and any(stream.hdr for stream in result.streams if stream.kind == 'video'):
                raise ComputeError(422, 'Tone mapped output still signals HDR')
            if video and video.hdr and target.mode != 'TRANSCODE':
                actual=next((s for s in result.streams if s.kind=='video'),None)
                if not actual or actual.hdr != video.hdr or actual.dv_profile != video.dv_profile:
                    raise ComputeError(422, 'Remuxed output lost HDR metadata')
            if result.duration <= 0 or media.duration > 0 and abs(result.duration - (media.duration-target.start)) > 2:
                raise ComputeError(422, 'Incomplete output')
            output.rename(directory / 'output.mp4')
        return [{'sourceId':s.id, 'outputIndex':i, 'kind':s.kind, 'language':s.language, 'title':s.title,
                 'channels':s.channels} for i,s in enumerate(([video] if video else []) + audios)]
