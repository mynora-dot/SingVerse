"""Authenticate and bound task bodies before FastAPI performs JSON parsing."""
from ..core.errors import ComputeError
from .errors import problem

class LanPairingBoundary:
    """Gate LAN requests by socket peer and bound bodies before parsing unauthenticated JSON."""
    PATHS = frozenset({'/internal/v1/lan/discovery', '/internal/v1/lan/challenge', '/internal/v1/lan/pair'})

    def __init__(self, app, limit=8192):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope['path'] not in self.PATHS:
            return await self.app(scope, receive, send)
        original_send = send
        async def send(message):
            if message['type'] == 'http.response.start':
                message['headers'] = list(message.get('headers', [])) + [(b'cache-control', b'private, no-store'),
                                                                         (b'x-content-type-options', b'nosniff')]
            await original_send(message)
        from urllib.parse import urlsplit
        from ..application.lan_pairing import private_peer
        headers = [(key.lower(), value) for key, value in scope.get('headers', [])]
        try:
            if any(key == b'origin' or key == b'forwarded' or key.startswith((b'x-forwarded', b'xforwarded')) for key, _ in headers):
                raise ComputeError(403, 'LAN pairing does not accept browser or proxy forwarding headers')
            client = scope.get('client')
            peer = private_peer(client[0] if isinstance(client, (tuple, list)) and client else '')
            hosts = [value for key, value in headers if key == b'host']
            if len(hosts) != 1:
                raise ComputeError(403, 'LAN pairing requires a private IP literal Host')
            try:
                host = urlsplit('//' + hosts[0].decode('ascii'))
                if host.username is not None or host.password is not None or host.path or host.query or host.fragment or host.port == 0:
                    raise ValueError()
                private_peer(host.hostname or '')
            except (ValueError, UnicodeError) as error:
                raise ComputeError(403, 'LAN pairing requires a private IP literal Host') from error
            scope.setdefault('state', {})['lan_peer'] = peer
        except ComputeError as error:
            return await problem(scope['path'], error.status, error.message)(scope, receive, send)
        if scope['method'] != 'POST':
            return await self.app(scope, receive, send)
        body = bytearray()
        while True:
            message = await receive()
            if message['type'] == 'http.disconnect':
                return
            if len(body) + len(message.get('body', b'')) > self.limit:
                return await problem(scope['path'], 413, 'Request too large')(scope, receive, send)
            body.extend(message.get('body', b''))
            if not message.get('more_body', False):
                break
        consumed = False
        async def replay():
            nonlocal consumed
            if consumed:
                return await receive()
            consumed = True
            return {'type': 'http.request', 'body': bytes(body), 'more_body': False}
        return await self.app(scope, replay, send)

class InternalTaskBoundary:
    def __init__(self, app, *, auth, paths: frozenset[str], limit: int):
        self.app, self.auth, self.paths, self.limit = app, auth, paths, limit

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope['method'] != 'POST' or scope['path'] not in self.paths:
            return await self.app(scope, receive, send)
        headers = {key.lower():value for key,value in scope.get('headers', [])}
        try:
            self.auth.authorize(headers.get(b'authorization', b'').decode('latin-1'))
        except ComputeError as error:
            return await problem(scope['path'], error.status, error.message)(scope, receive, send)
        body = bytearray()
        while True:
            message = await receive()
            if message['type'] == 'http.disconnect':
                return
            chunk = message.get('body', b'')
            if len(body) + len(chunk) > self.limit:
                return await problem(scope['path'], 413, 'Request too large')(scope, receive, send)
            body.extend(chunk)
            if not message.get('more_body', False):
                break
        consumed = False
        async def replay():
            nonlocal consumed
            if consumed:
                return await receive()
            consumed = True
            return {'type':'http.request', 'body':bytes(body), 'more_body':False}
        return await self.app(scope, replay, send)


class RequestContext:
    """Per-request correlation without global mutable context or payload logging."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        import logging
        import time
        import uuid
        request_id = uuid.uuid4().hex
        scope.setdefault('state', {})['request_id'] = request_id
        started = time.monotonic()
        status = 500
        async def response(message):
            nonlocal status
            if message['type'] == 'http.response.start':
                status = message['status']
                message['headers'] = list(message.get('headers', [])) + [(b'x-request-id', request_id.encode())]
            await send(message)
        try:
            return await self.app(scope, receive, response)
        finally:
            logging.getLogger('singverse_compute.http').info('request.complete', extra={
                'request_id':request_id, 'method':scope['method'], 'path':scope['path'],
                'status':status, 'duration_ms':round((time.monotonic()-started)*1000, 2)})
