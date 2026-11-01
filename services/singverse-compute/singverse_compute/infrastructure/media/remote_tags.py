"""Bounded, task-scoped remote metadata. No database or WebDAV credentials."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import RawIOBase
import base64
import json
import secrets
import socket
import ipaddress
import threading
import time
from functools import lru_cache
from urllib.request import Request, build_opener
from urllib.error import HTTPError
from urllib.parse import urlsplit
from ..transcoding.input import NoRedirect
from ..transcoding.process import run
from .ffprobe import normalize
from ...core.errors import ComputeError

BLOCK = 1024 * 1024
FORMATS = 'mp3,flac,ogg,wav,mov,matroska,webm,avi,asf,mpeg,mpegts,aac,ape,aiff'

class ReadStopped(Exception):
    def __init__(self, reason):
        self.reason = reason

class RangeFile(RawIOBase):
    """Read-only seekable object; every consumer shares a bounded block cache."""
    def __init__(self, origin, reference, token, name, size, deep=False):
        super().__init__()
        parsed = urlsplit(origin)
        if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
            raise ComputeError(503, 'Java input origin is not configured')
        for item in socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80), type=socket.SOCK_STREAM):
            ip = ipaddress.ip_address(item[4][0])
            if ip.is_link_local or ip.is_multicast or ip.is_unspecified or parsed.scheme == 'http' and not (ip.is_private or ip.is_loopback):
                raise ComputeError(403, 'Java input origin requires TLS or restricted network')
        self.url = origin + '/api/v1/internal/metadata-input/' + reference
        self.token, self.name = token, name
        self.size, self.position = size, 0
        self.limit = (128 if deep else 16) * BLOCK
        self.deadline = time.monotonic() + (180 if deep else 60)
        self.max_requests = 64 if deep else 16
        self.cache = {}
        self.bytes_read = self.requests = 0
        self.reason = None
        self.lock = threading.RLock()

    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.position
    def seek(self, offset, whence=0):
        target = offset if whence == 0 else self.position + offset if whence == 1 else self.length() + offset
        if target < 0: raise ValueError('Negative seek')
        self.position = target
        return target

    def stop(self, reason):
        self.reason = reason
        raise ReadStopped(reason)

    def block(self, index):
        with self.lock:
            if index in self.cache: return self.cache[index]
            if self.reason: raise ReadStopped(self.reason)
            if time.monotonic() >= self.deadline or self.requests >= self.max_requests or self.bytes_read >= self.limit:
                self.stop('BUDGET_EXCEEDED')
            start = index * BLOCK
            end = min(start + BLOCK - 1, start + self.limit - self.bytes_read - 1)
            if self.size is not None:
                if start >= self.size: return b''
                end = min(end, self.size - 1)
            self.requests += 1
            req = Request(self.url, headers={'Authorization': 'Bearer ' + self.token, 'Range': f'bytes={start}-{end}', 'Accept-Encoding': 'identity'})
            try:
                with build_opener(NoRedirect()).open(req, timeout=max(0.1, min(20, self.deadline-time.monotonic()))) as response:
                    if response.status != 206: self.stop('RANGE_UNSUPPORTED')
                    import re
                    match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
                    if not match or int(match[1]) != start or int(match[2]) != min(end, int(match[3])-1): self.stop('UNSTABLE')
                    length = int(match[3])
                    if self.size is not None and length != self.size: self.stop('UNSTABLE')
                    self.size = length
                    data = response.read(end-start+2)
                    self.bytes_read += len(data)
                    if len(data) != int(match[2])-start+1: self.stop('UNSTABLE')
                    self.cache[index] = data
                    return data
            except HTTPError as error:
                error.close()
                self.stop({401:'AUTH_REQUIRED',403:'AUTH_REQUIRED',404:'UNSTABLE',409:'UNSTABLE',410:'UNSTABLE',412:'UNSTABLE',413:'BUDGET_EXCEEDED',422:'RANGE_UNSUPPORTED',429:'RATE_LIMITED'}.get(error.code, 'NETWORK_ERROR'))
            except (OSError, TimeoutError): self.stop('NETWORK_ERROR')

    def length(self):
        if self.size is None: self.block(0)
        return self.size

    def read(self, size=-1):
        with self.lock:
            if size < 0: size = self.length() - self.position
            size = min(size, self.length()-self.position)
            if size <= 0: return b''
            # Reject unbounded allocations before reading, including huge malicious tags.
            if size > self.limit: self.stop('BUDGET_EXCEEDED')
            output = bytearray()
            while len(output) < size:
                index, offset = divmod(self.position, BLOCK)
                data = self.block(index)
                part = data[offset:offset+size-len(output)]
                if not part: break
                output.extend(part)
                self.position += len(part)
            return bytes(output)

@contextmanager
def loopback(source):
    route = '/' + secrets.token_hex(24)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_HEAD(self): self.transfer(True)
        def do_GET(self): self.transfer(False)
        def transfer(self, head):
            if self.path != route: self.send_error(404); return
            try:
                with source.lock:
                    size = source.length()
                start, end = 0, size-1
                value = self.headers.get('Range')
                if value:
                    import re
                    m = re.fullmatch(r'bytes=(\d+)-(\d*)', value)
                    if not m: self.send_error(416); return
                    start, end = int(m[1]), min(int(m[2]) if m[2] else end, end)
                if start > end: self.send_error(416); return
                self.send_response(206 if value else 200)
                self.send_header('Content-Length', str(end-start+1))
                self.send_header('Accept-Ranges', 'bytes')
                if value: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                self.end_headers()
                if not head:
                    position = start
                    while position <= end:
                        # Never hold the file/cache lock while writing to a player socket:
                        # ffprobe may seek using another connection before draining this one.
                        with source.lock:
                            source.seek(position)
                            chunk = source.read(min(65536, end-position+1))
                        if not chunk: break
                        self.wfile.write(chunk)
                        position += len(chunk)
            except (ReadStopped, OSError): self.close_connection = True
    server = ThreadingHTTPServer(('127.0.0.1',0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try: yield f'http://127.0.0.1:{server.server_port}{route}'
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=2)


def audio_extras(source):
    import mutagen
    if source.name.rsplit('.',1)[-1].lower() not in ('mp3','flac','m4a','mp4','ogg','oga','opus'):
        return {},None,None,False
    source.seek(0)
    header=source.read(10)
    if header.startswith(b'ID3'):
        version=header[3]
        if version not in (3,4) or header[5]&0xc0 or any(v&0x80 for v in header[6:10]):
            raise ValueError('Unsupported ID3 transform; use bounded subprocess probe')
        length=sum(value<<(7*(3-i)) for i,value in enumerate(header[6:10]))
        raw=source.read(length)
        offset=count=0
        while offset+10<=len(raw) and raw[offset:offset+4].strip(b'\x00'):
            count+=1
            if count>4096:raise ValueError('Too many tag frames')
            size_bytes=raw[offset+4:offset+8]
            size=sum(value<<(7*(3-i)) for i,value in enumerate(size_bytes)) if version==4 else int.from_bytes(size_bytes,'big')
            flags=int.from_bytes(raw[offset+8:offset+10],'big')
            if flags&(0x0008 if version==4 else 0x0080):
                raise ValueError('Compressed ID3 frames require bounded subprocess probe')
            if size<=0 or offset+10+size>len(raw):break
            offset+=10+size
    source.seek(0)
    media = mutagen.File(source)
    if media is None: return {}, None, None, False
    tags = media.tags or {}
    lyrics = None
    cover = None
    values = {}
    # ID3 stores structured frames; Vorbis/FLAC/MP4 store list values.
    if hasattr(tags, 'getall'):
        for field, key in [('title','TIT2'),('artists','TPE1'),('album','TALB'),('releaseDate','TDRC')]:
            frames = tags.getall(key)
            if frames:
                text = [str(x)[:500] for x in frames[0].text if str(x).strip()]
                if text: values[field] = text if field == 'artists' else text[0]
        pictures = sorted(tags.getall('APIC'), key=lambda p: p.type != 3)
        if pictures: cover = pictures[0].data
        unsync, sync = tags.getall('USLT'), tags.getall('SYLT')
        if sync:
            frame = sync[0]
            # SYLT format 2 uses milliseconds; frame-count timestamps must not be mislabeled.
            lyrics = {'text':'\n'.join(str(t) for t,_ in frame.text), 'language':frame.lang,
                      'timing':'milliseconds' if frame.format == 2 else 'mpeg-frames',
                      'lines':[{'text':str(t),'time':int(v)} for t,v in frame.text]}
        elif unsync: lyrics = {'text':unsync[0].text, 'language':unsync[0].lang, 'timing':'none'}
    else:
        for field, keys in [('title',['title','\xa9nam']),('artists',['artist','\xa9ART']),('album',['album','\xa9alb']),('releaseDate',['date','\xa9day'])]:
            for key in keys:
                if key in tags:
                    text = [str(v)[:500] for v in tags[key]]
                    if text: values[field] = text if field == 'artists' else text[0]
                    break
        for key in ('lyrics','LYRICS','unsyncedlyrics','\xa9lyr'):
            if key in tags:
                text = str(tags[key][0])
                lyrics = {'text':text, 'timing':'lrc' if __import__('re').search(r'\[\d+:\d+',text) else 'none'}
                break
        pictures = getattr(media, 'pictures', [])
        if pictures: cover = sorted(pictures,key=lambda p:p.type != 3)[0].data
        elif tags.get('covr'): cover = bytes(tags['covr'][0])
        elif tags.get('metadata_block_picture'):
            from mutagen.flac import Picture
            cover = Picture(base64.b64decode(tags['metadata_block_picture'][0],validate=True)).data
    return values, cover, lyrics, True


@lru_cache(maxsize=8)
def http_options(executable):
    try:
        help_text=run([executable,'-hide_banner','-h','protocol=http'],timeout=3,limit=65536).decode('utf-8','replace')
        return ['-request_size',str(BLOCK),'-initial_request_size',str(BLOCK)] if '-initial_request_size' in help_text else []
    except Exception: return []

def extract(source, ffprobe, ffmpeg, memory_limit):
    started = time.monotonic()
    result = {'extractorVersion':'remote-tags-1','tags':{},'technical':{},'lyrics':None,'cover':None,'fields':{}}
    parsed = audio_supported = False
    cover = lyrics = None
    try:
        tags, cover, lyrics, audio_supported = audio_extras(source)
        result['tags'].update(tags)
    except ReadStopped: pass
    except Exception: pass  # Unsupported/malformed tags do not prevent the bounded probe.
    try:
        with loopback(source) as url:
            command = [ffprobe,'-v','error','-protocol_whitelist','http,tcp','-format_whitelist',FORMATS,
                       '-probesize','2097152','-analyzeduration','1000000','-show_format','-show_streams','-of','json',*http_options(ffprobe),url]
            data = json.loads(run(command,timeout=max(.1,source.deadline-time.monotonic()),limit=1024*1024,memory_limit=memory_limit))
            parsed = True
            raw_tags = {str(k).lower(): v for k,v in data.get('format',{}).get('tags',{}).items()}
            if not lyrics:
                for key in ('lyrics','unsyncedlyrics','syncedlyrics'):
                    if isinstance(raw_tags.get(key),str) and raw_tags[key].strip():
                        lyrics = {'text':raw_tags[key], 'timing':'lrc' if __import__('re').search(r'\[\d+:\d+',raw_tags[key]) else 'none'}
                        break
            for key,value in normalize(data).items():
                if value: result['tags'].setdefault(key,value)
            fmt = data.get('format',{})
            streams = data.get('streams',[])[:64]
            result['technical'] = {'container':fmt.get('format_name'),'duration':fmt.get('duration'),'bitrate':fmt.get('bit_rate'),
                'streams':[{k:s[k] for k in ('index','codec_type','codec_name','width','height','channels','sample_rate','bit_rate','avg_frame_rate') if k in s}
                           for s in streams if not s.get('disposition',{}).get('attached_pic')]}
            # Only attached images or explicitly named cover attachments, never video frames.
            images = [s for s in streams if s.get('disposition',{}).get('attached_pic')]
            images += [s for s in streams if s.get('codec_type') == 'attachment' and str(s.get('tags',{}).get('filename','')).lower().split('.')[0] in ('cover','front','folder') and s.get('codec_name') in ('png','mjpeg')]
            if not cover and images and not source.reason:
                stream = images[0]
                if stream.get('codec_type') == 'attachment':
                    # dump_attachment writes only the selected embedded attachment to stdout.
                    cmd = [ffmpeg,'-v','error','-protocol_whitelist','http,tcp','-dump_attachment:'+str(stream['index']),'pipe:1',*http_options(ffmpeg),'-i',url,'-t','0','-f','null','-']
                else:
                    cmd = [ffmpeg,'-v','error','-protocol_whitelist','http,tcp',*http_options(ffmpeg),'-i',url,'-map','0:'+str(stream['index']),'-c','copy','-frames:v','1','-f','image2pipe','pipe:1']
                try: cover = run(cmd,timeout=max(.1,source.deadline-time.monotonic()),limit=2_000_000,memory_limit=memory_limit)
                except Exception: result['fields']['cover'] = 'UNKNOWN'
    except Exception: pass
    if cover:
        if len(cover) <= 2_000_000: result['cover'] = base64.b64encode(cover).decode('ascii')
        else: result['fields']['cover'] = 'UNKNOWN'
    if lyrics:
        if len(json.dumps(lyrics,ensure_ascii=False).encode()) <= 262144: result['lyrics'] = lyrics
        else: result['fields']['lyrics'] = 'UNKNOWN'
    if not parsed: result['fields']['technical'] = 'UNKNOWN'
    for field in ('title','artists','album','releaseDate','technical','cover','lyrics'):
        value = result['tags'].get(field) if field in ('title','artists','album','releaseDate') else result.get(field)
        result['fields'].setdefault(field,'PRESENT' if value else 'ABSENT' if not source.reason and (audio_supported or parsed) and field not in ('cover','lyrics') else 'ABSENT' if audio_supported and not source.reason else 'UNKNOWN')
    for key in ('title','album'):
        if isinstance(result['tags'].get(key),str): result['tags'][key] = result['tags'][key][:500]
    if result['tags'].get('artists'):
        result['tags']['artists'] = [p.strip()[:200] for value in result['tags']['artists'] for p in str(value).split(';') if p.strip()][:32]
    if result['tags'].get('releaseDate'): result['tags']['releaseDate'] = str(result['tags']['releaseDate'])[:32]
    result['reason'] = source.reason
    if source.reason in ('AUTH_REQUIRED','RATE_LIMITED','NETWORK_ERROR','UNSTABLE'):
        result['state'] = source.reason
    elif source.reason or any(v == 'UNKNOWN' for v in result['fields'].values()): result['state'] = 'PARTIAL' if any(v == 'PRESENT' for v in result['fields'].values()) else source.reason or 'UNSUPPORTED'
    else: result['state'] = 'COMPLETE' if result['tags'] or result['cover'] or result['lyrics'] else 'EMPTY'
    result.update(bytesRead=source.bytes_read,requestCount=source.requests,elapsedMs=int((time.monotonic()-started)*1000))
    return result
