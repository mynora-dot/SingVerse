"""Stable ASGI import compatibility; application wiring lives in bootstrap."""
from .bootstrap import create_app

app = create_app()
