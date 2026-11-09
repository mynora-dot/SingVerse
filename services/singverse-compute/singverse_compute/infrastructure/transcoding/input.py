"""Task-scoped loopback reader for authenticated Java callback references."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError
import threading
import secrets
import ipaddress
import socket
from urllib.parse import urlsplit
from ...core.errors import ComputeError

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None

def validate_origin(origin):
    if not isinstance(origin, str) or len(origin) > 512:
        raise ComputeError(422, 'Business callback origin is required')
    try:
        parsed = urlsplit(origin)
        if (parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username
                or parsed.password or parsed.path or parsed.query or parsed.fragment or parsed.port == 0):
            raise ValueError()
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80), type=socket.SOCK_STREAM)
        for address in addresses:
            value = ipaddress.ip_address(address[4][0])
            if value.is_link_local or value.is_multicast or value.is_unspecified or parsed.scheme == 'http' and not (value.is_private or value.is_loopback):
                raise ComputeError(403, 'Business callback requires TLS or a restricted private network')
    except (OSError, ValueError) as error:
        raise ComputeError(422, 'Business callback origin is invalid or unavailable') from error
    return origin

@contextmanager
def remote_input(origin, reference, token, resource='transcode-input'):
    validate_origin(origin)
    if resource not in ('transcode-input', 'audio-input'):
        raise ComputeError(422, 'Invalid media resource')
    route = '/' + secrets.token_hex(24)
    active = threading.Event()
    active.set()
    failure = []
    class Reader(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_HEAD(self):
            self.transfer(True)
        def do_GET(self):
            self.transfer(False)
        def transfer(self, head):
            if self.path != route or not active.is_set():
                self.send_error(404)
                return
            headers = {'Authorization': 'Bearer ' + token, 'Accept-Encoding': 'identity'}
            if self.headers.get('Range'):
                headers['Range'] = self.headers['Range']
            try:
                request = Request(origin + '/api/v1/internal/' + resource + '/' + reference,
                                  headers=headers, method='HEAD' if head else 'GET')
                with build_opener(NoRedirect()).open(request, timeout=15) as response:
                    self.send_response(response.status)
                    for name in ('Content-Length', 'Content-Range', 'Accept-Ranges', 'Content-Type'):
                        if response.headers.get(name):
                            self.send_header(name, response.headers[name])
                    self.end_headers()
                    if not head:
                        while active.is_set() and (chunk := response.read(65536)):
                            try:
                                self.wfile.write(chunk)
                            except (BrokenPipeError, ConnectionResetError):
                                # FFprobe/FFmpeg can stop a range early after reading enough.
                                # A reader closing normally is not a failed business origin.
                                self.close_connection = True
                                return
            except HTTPError as error:
                failure.append(f'Media callback returned HTTP {error.code}')
                self.send_error(error.code)
                error.close()
            except OSError:
                failure.append('Media callback connection failed')
                self.close_connection = True
    server = ThreadingHTTPServer(('127.0.0.1', 0), Reader)
    server.daemon_threads = True
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.02), daemon=True)
    thread.start()
    try:
        try:
            yield f'http://127.0.0.1:{server.server_port}{route}'
        except ComputeError as error:
            if failure:
                raise ComputeError(422, failure[-1]) from error
            raise
    finally:
        active.clear()
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
