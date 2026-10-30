"""Task-scoped loopback reader: upstream origin is deployment configuration only."""
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

@contextmanager
def remote_input(origin, reference, token):
    parsed = urlsplit(origin)
    try:
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80), type=socket.SOCK_STREAM)
        for address in addresses:
            value=ipaddress.ip_address(address[4][0])
            if value.is_link_local or value.is_multicast or value.is_unspecified or parsed.scheme == 'http' and not (value.is_private or value.is_loopback):
                raise ComputeError(403, 'Java input origin must use TLS or a restricted private network')
    except OSError as error:
        raise ComputeError(503, 'Java input origin unavailable') from error
    route = '/' + secrets.token_hex(24)
    class Reader(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_HEAD(self):
            self.transfer(True)
        def do_GET(self):
            self.transfer(False)
        def transfer(self, head):
            if self.path != route:
                self.send_error(404)
                return
            headers = {'Authorization': 'Bearer ' + token, 'Accept-Encoding': 'identity'}
            if self.headers.get('Range'):
                headers['Range'] = self.headers['Range']
            try:
                request = Request(origin + '/api/v1/internal/transcode-input/' + reference,
                                  headers=headers, method='HEAD' if head else 'GET')
                with build_opener(NoRedirect()).open(request, timeout=15) as response:
                    self.send_response(response.status)
                    for name in ('Content-Length', 'Content-Range', 'Accept-Ranges', 'Content-Type'):
                        if response.headers.get(name):
                            self.send_header(name, response.headers[name])
                    self.end_headers()
                    if not head:
                        while chunk := response.read(65536):
                            self.wfile.write(chunk)
            except (OSError, HTTPError):
                self.close_connection = True
    server = ThreadingHTTPServer(('127.0.0.1', 0), Reader)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}{route}'
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
