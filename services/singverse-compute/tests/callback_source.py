"""Business-side HTTP fixture; compute has no media roots or filesystem grant."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import uuid

class CallbackSource:
    def __init__(self, root, token):
        self.root, self.token = Path(root).resolve(), token
        self.grants = {}
        self.requests = []
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self): self.transfer(False)
            def do_HEAD(self): self.transfer(True)
            def transfer(self, head):
                owner.requests.append((self.command, self.headers.get('Range')))
                if self.headers.get('Authorization') != 'Bearer ' + owner.token:
                    self.send_error(401); return
                path = owner.grants.get(self.path.rsplit('/', 1)[-1])
                if path is None:
                    self.send_error(410); return
                file = Path(path).resolve()
                if not file.is_relative_to(owner.root) or not file.is_file():
                    self.send_error(403); return
                data = file.read_bytes(); start, end = 0, len(data) - 1
                ranged = self.headers.get('Range')
                if ranged:
                    values = ranged.removeprefix('bytes=').split('-')
                    start = int(values[0]); end = min(end, int(values[1]) if values[1] else end)
                if start > end:
                    self.send_response(416); self.send_header('Content-Range', f'bytes */{len(data)}'); self.end_headers(); return
                self.send_response(206 if ranged else 200)
                self.send_header('Content-Length', str(end-start+1)); self.send_header('Accept-Ranges', 'bytes')
                if ranged: self.send_header('Content-Range', f'bytes {start}-{end}/{len(data)}')
                self.end_headers()
                if not head:
                    try: self.wfile.write(data[start:end+1])
                    except (BrokenPipeError, ConnectionResetError): pass
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.origin = f'http://127.0.0.1:{self.server.server_port}'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
    def grant(self, file):
        reference = str(uuid.uuid5(uuid.NAMESPACE_URL, str(file)))
        self.grants[reference] = file
        return {'reference': reference, 'callbackOrigin': self.origin}
    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(2)
