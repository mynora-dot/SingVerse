"""Centralized Problem Details mapping without echoing request values."""
from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from ..core.errors import ComputeError

def problem(path: str, status: int, title: str, headers=None):
    return JSONResponse(status_code=status, media_type='application/problem+json', headers=headers,
                        content={'type':'about:blank', 'title':title, 'status':status, 'instance':path})

def register_errors(app):
    @app.exception_handler(ComputeError)
    async def compute_error(request: Request, error: ComputeError):
        return problem(request.url.path, error.status, error.message)

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, error: HTTPException):
        return problem(request.url.path, error.status_code, str(error.detail), error.headers)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, error: RequestValidationError):
        return problem(request.url.path, 422, 'Invalid request')


def problem_responses(statuses: dict[int, str]):
    from .schemas import Problem
    return {status:{'description':description, 'content':{'application/problem+json':{'schema':Problem.model_json_schema()}}}
            for status,description in statuses.items()}
