#!/usr/bin/env python3
"""Generate original test tones/video; optionally seed a disposable loopback NAS."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import subprocess
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path)
parser.add_argument('--seed', action='store_true')
parser.add_argument('--nas', default='http://127.0.0.1:18181')
args = parser.parse_args()
root = args.directory.resolve()
root.mkdir(parents=True, exist_ok=True)
marker = root / '.singverse-karaoke-fixture'

if not args.seed:
    if any(root.iterdir()):
        raise SystemExit('Use a new, empty fixture directory; existing files are never overwritten.')
    media = root / 'media'
    media.mkdir()
    def generate(*options):
        subprocess.run([str(Path(__file__).resolve().parents[1] / '.tools/jellyfin-ffmpeg/ffmpeg'), '-v', 'error', '-nostdin', *options], check=True)
    audio = '星河合唱团-晨光练习-伴奏'
    video = '星河合唱团-晚风练习-国语'
    generate('-f', 'lavfi', '-i', 'sine=frequency=220:duration=90:sample_rate=48000',
             '-ac', '2', str(media / (audio + '.wav')))
    generate('-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25:duration=90',
             '-f', 'lavfi', '-i', 'sine=frequency=440:duration=90:sample_rate=48000',
             '-f', 'lavfi', '-i', 'sine=frequency=220:duration=90:sample_rate=48000',
             '-map', '0:v', '-map', '1:a', '-map', '2:a', '-c:v', 'libx264',
             '-preset', 'ultrafast', '-c:a', 'aac', '-metadata:s:a:0', 'title=导唱',
             '-metadata:s:a:1', 'title=伴奏', str(media / (video + '.mkv')))
    generate('-i', str(media / (video + '.mkv')), '-map', '0', '-c', 'copy', '-metadata',
             'title=视觉验收 · 晚风里唱给家人的一首歌，长歌名也应清楚显示并可以顺畅操作',
             str(media / 'visual-long.mkv'))
    phrases = ['晨光落在窗前', '把心事轻轻唱出来', '熟悉的旋律还在', '每一次开口都值得期待', '让声音慢慢盛开']
    text = '\n'.join(f'[{i*6//60:02d}:{i*6%60:02d}.00]{phrases[i % len(phrases)]}' for i in range(15))
    for name in (audio, video, 'visual-long'):
        (media / (name + '.lrc')).write_text(text, encoding='utf-8')
    generate('-f', 'lavfi', '-i', 'sine=frequency=880:duration=8:sample_rate=48000',
             str(root / 'voice-test.wav'))
    marker.write_text('Original generated tones and lyrics for disposable emulator tests.\n')
    print('Fixtures generated. Start isolated Java/compute with this directory authorized, then use --seed.')
else:
    if not marker.is_file():
        raise SystemExit('Refusing to seed without this tool’s fixture marker.')
    endpoint = urllib.parse.urlsplit(args.nas)
    if endpoint.hostname not in ('127.0.0.1', 'localhost') or endpoint.port != 18181:
        raise SystemExit('Seeding is restricted to the disposable loopback NAS on port 18181.')
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(path, method='GET', body=None):
        headers = {'Content-Type': 'application/json'}
        if method != 'GET':
            headers['X-CSRF-Token'] = request('/api/v1/auth/csrf')['token']
        data = json.dumps(body).encode() if body is not None else (b'' if method != 'GET' else None)
        with client.open(urllib.request.Request(args.nas + path, data=data, headers=headers, method=method), timeout=90) as response:
            result = response.read()
            return json.loads(result) if result else None
    # Factory credentials belong exclusively to the disposable test database.
    request('/api/v1/auth/login', 'POST', {'username': 'admin', 'password': 'admin'})
    source = request('/api/v1/media-sources', 'POST', {'name': '模拟器验收样本', 'type': 'LOCAL', 'location': str(root / 'media')})
    request('/api/v1/media-sources/' + source['id'] + '/scan', 'POST')
    songs = request('/api/v1/mobile/library/songs?size=100')['items']
    for song in songs:
        if song['sourceName'] != '模拟器验收样本' or song['format'] not in ('WAV', 'MKV'):
            continue
        review = request('/api/v1/metadata/songs/' + song['id'] + '/review')
        request('/api/v1/metadata/songs/' + song['id'] + '/review/apply', 'POST', {
            'requestId': str(uuid.uuid4()), 'fingerprint': review['fingerprint'], 'version': review['version'],
            'fields': ['title', 'artists'], 'edits': {
                'title': '晨光练习' if song['format'] == 'WAV' else '晚风练习', 'artists': ['星河合唱团']}})
    print('Disposable NAS seeded through authenticated source, scan and metadata review APIs.')
