"""Credential-free discovery and encrypted pairing for direct private-LAN servers."""
from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field

from ..errors import problem_responses

router = APIRouter(prefix='/internal/v1/lan', tags=['LAN pairing'])
UUID = r'^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$'
HEX = r'^[a-f0-9]{64}$'


class Discovery(BaseModel):
    service: str = 'singverse-compute'
    apiVersion: str = '1'
    nodeId: str = Field(pattern=UUID)
    name: str
    ownerId: str
    pairingEnabled: bool


class ChallengeRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    nodeId: str = Field(pattern=UUID)
    clientId: str = Field(pattern=HEX)


class Challenge(BaseModel):
    nodeId: str = Field(pattern=UUID)
    challenge: str = Field(pattern=HEX)


class PairRequest(ChallengeRequest):
    publicKey: str = Field(min_length=1, max_length=2048)
    challenge: str = Field(pattern=HEX)
    signature: str = Field(min_length=1, max_length=2048)


class PairResult(Challenge):
    encryptedToken: str


@router.get('/discovery', response_model=Discovery, summary='私网单播节点发现，不返回凭据',
            responses=problem_responses({403: 'Direct private peer and IP literal Host required', 503: 'Persistent identity unavailable'}))
def discovery(request: Request):
    return request.app.state.lan_pairing.discovery(request.state.lan_peer)


@router.post('/challenge', response_model=Challenge, summary='创建绑定节点、客户端身份和私网来源的一次性挑战',
             responses=problem_responses({403: 'Pairing disabled or private boundary rejected', 409: 'Node already owned or identity changed',
                                         413: 'Request too large', 429: 'Challenge or rate limit reached', 503: 'Persistent identity unavailable'}))
def challenge(request: Request, body: ChallengeRequest):
    return request.app.state.lan_pairing.challenge(request.state.lan_peer, body.nodeId, body.clientId)


@router.post('/pair', response_model=PairResult, summary='签名配对或同身份恢复，仅返回 RSA 加密后的内部凭据',
             responses=problem_responses({401: 'Invalid signature, challenge or client identity', 403: 'Pairing disabled or private boundary rejected',
                                         409: 'Node already owned or identity changed', 413: 'Request too large',
                                         429: 'Rate limit reached', 503: 'Persistent identity unavailable'}))
def pair(request: Request, body: PairRequest):
    return request.app.state.lan_pairing.pair(request.state.lan_peer, body.nodeId, body.clientId,
                                            body.publicKey, body.challenge, body.signature)
