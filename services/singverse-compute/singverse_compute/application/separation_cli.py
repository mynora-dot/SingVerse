"""Local administrator CLI using the production recipe and worker lifecycle.

This accepts local paths only. HTTP callers continue to use Java-authorized references.
No song is downloaded, and original files are read-only.
"""
import argparse
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import time
import uuid

from ..core.config import Settings
from ..domain.audio_separation import CLEAN, DSP, LEGACY, recipe


class LocalEngine:
    def __init__(self, settings, cache):
        from ..bootstrap import create_app
        self.app = create_app(replace(settings, transcode_root=cache, token_file=cache/'cli.token', lan_pairing_enabled=False))
        self.audio = self.app.state.audio_roles

    def qualify(self, backend=None):
        self.audio.separation.qualify('cpu')
        if backend and backend not in ('auto', 'cpu'):
            self.audio.separation.qualify(backend)

    def process(self, source, target, profile=DSP, backend=None, output_format='aac', preview=False):
        from .audio_roles import AudioTask
        source, target = Path(source).resolve(strict=True), Path(target).resolve()
        if target.exists() or source == target:
            raise ValueError('Output already exists; use a separate derivative path')
        target.parent.mkdir(parents=True, exist_ok=True)
        task = AudioTask(str(uuid.uuid4())); task.recipe = recipe(profile, output_format=output_format, preview=preview)
        task.result.update(task.recipe); task.cpu_fallback = True
        task.backend = self.audio.separation.admit(profile, backend) if profile != LEGACY else self.audio.models.selected()
        if not task.backend:
            raise ValueError('Legacy model must be installed and qualified on this node')
        started = time.monotonic()
        partial = target.with_name(target.name+'.part')
        if partial.exists():
            raise ValueError('A partial output already exists')
        try:
            with self.audio.stage(task, 'probe'):
                media = self.audio.transcoding.probe.read_audio(str(source), cancel=task.cancel)
            audio = [s for s in media.streams if s.kind == 'audio']
            with tempfile.TemporaryDirectory(dir=self.audio.root, prefix='cli-') as directory:
                if profile == LEGACY:
                    if len(audio) != 1:
                        raise ValueError('Local separation needs exactly one audio track')
                    seconds = min(12, media.duration) if preview else media.duration
                    work = Path(directory); wav = work/'source.wav'
                    self.audio._extract(str(source), False, audio[0].index, 0, seconds, wav, task)
                    _, backing = self.audio._separate(wav, work/'out', task)
                    from ..infrastructure.dsp_audio import process
                    process(backing, partial, ffmpeg=self.audio.settings.ffmpeg, center_cancel=False, output_format=output_format)
                else:
                    _, seconds, _ = self.audio.separation.process(str(source), False, audio, media.duration, Path(directory), partial, task)
            output = self.audio.transcoding.probe.read_audio(str(partial))
            if abs(output.duration-seconds) > .25 or abs(output.start_time) > .025:
                raise ValueError('Output timeline did not match the recipe')
            # No overwrite even if another local CLI finishes the same path concurrently.
            import os
            os.link(partial, target)
            task.result.update(duration=seconds, sourceDuration=media.duration, sha256=self.audio.digest(target),
                               inputSha256=self.audio.digest(source), output=str(target))
            task.result.setdefault('timingsMs', {})['total'] = round((time.monotonic()-started)*1000, 3)
            task.result['rtf'] = task.result['timingsMs']['total']/1000/seconds
            task.result['measurement'] = 'measured-local-adapter'
            task.result['stage'] = 'SUCCEEDED'
            task.result['scope'] = 'local source through decode/separation/encode/validation/hash; excludes Java queue, fetch and upload'
            return task.result
        finally:
            partial.unlink(missing_ok=True)

    def close(self):
        self.audio.models.close()
        self.audio.close()
        self.app.state.lan_pairing.close()


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--profile', choices=[DSP, CLEAN, LEGACY], default=DSP)
    parser.add_argument('--backend', default=None)
    parser.add_argument('--format', choices=['aac', 'flac'], default='aac')
    parser.add_argument('--preview', action='store_true')
    parser.add_argument('--qualify', action='store_true', help='Qualify independent CPU, then the requested accelerator')
    parser.add_argument('--cache', type=Path, default=Path('.run/audio-cli'))
    return parser


def main():
    args = arguments().parse_args()
    cache = args.cache.resolve(); cache.mkdir(parents=True, exist_ok=True)
    engine = LocalEngine(Settings.from_env(), cache)
    try:
        if args.qualify and args.profile == CLEAN:
            engine.qualify(args.backend)
        print(json.dumps(engine.process(args.source, args.output, args.profile, args.backend, args.format, args.preview), ensure_ascii=False))
    finally:
        engine.close()


if __name__ == '__main__':
    main()
