"""Authenticate and bound task bodies before FastAPI performs JSON parsing."""
from ..core.errors import ComputeError
from .errors import problem

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
