from fastapi import APIRouter, Depends
from ...application.media_tags import MediaTagService
from ..dependencies import media_tags, require_internal_auth
from ..errors import problem_responses
from ..schemas import TagInput, Tags

TAG_PATH = '/internal/v1/media/tags'
router = APIRouter()

@router.post(TAG_PATH, response_model=Tags, dependencies=[Depends(require_internal_auth)], summary='只读读取允许目录内的音视频标签',
             responses=problem_responses({401:'Unauthorized',403:'Path forbidden',413:'Request too large',
                                           422:'Invalid request',429:'Reader busy',503:'Reader unavailable'}))
def read_tags(body: TagInput, service: MediaTagService = Depends(media_tags)) -> Tags:
    result = service.read(body.path)
    return Tags(title=result.title, artists=list(result.artists), album=result.album, releaseDate=result.release_date)
