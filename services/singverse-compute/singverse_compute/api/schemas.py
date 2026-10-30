"""Versioned HTTP DTOs; domain objects never depend on these models."""
from typing import Literal
from pydantic import BaseModel, Field

class Health(BaseModel):
    status: Literal['UP'] = 'UP'

class Capabilities(BaseModel):
    service: Literal['singverse-compute'] = 'singverse-compute'
    apiVersion: Literal['1'] = '1'
    capabilities: list[str] = Field(default_factory=list)

class TagInput(BaseModel):
    path: str = Field(min_length=1, max_length=4096)

class Tags(BaseModel):
    title: str | None = None
    artists: list[str] = Field(default_factory=list)
    album: str | None = None
    releaseDate: str | None = None

class Problem(BaseModel):
    type: str = 'about:blank'
    title: str
    status: int
    instance: str
