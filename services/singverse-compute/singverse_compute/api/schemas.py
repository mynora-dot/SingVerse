"""Versioned HTTP DTOs; domain objects never depend on these models."""
from typing import Literal
from pydantic import BaseModel, Field

class Health(BaseModel):
    status: Literal['UP'] = 'UP'

class Capabilities(BaseModel):
    service: Literal['singverse-compute'] = 'singverse-compute'
    apiVersion: Literal['1'] = '1'
    capabilities: list[str] = Field(default_factory=list)

class Problem(BaseModel):
    type: str = 'about:blank'
    title: str
    status: int
    instance: str
