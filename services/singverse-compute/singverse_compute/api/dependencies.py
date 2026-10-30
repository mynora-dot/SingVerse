"""Request-scoped access to application-owned service instances."""
from fastapi import Request, Security
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from ..application.media_tags import MediaTagService

def media_tags(request: Request) -> MediaTagService:
    return request.app.state.media_tags


bearer = HTTPBearer(auto_error=False)

def require_internal_auth(request: Request, credentials: HTTPAuthorizationCredentials | None = Security(bearer)):
    authorization = '' if credentials is None else credentials.scheme + ' ' + credentials.credentials
    request.app.state.internal_auth.authorize(authorization)
