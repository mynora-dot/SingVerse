"""JSON logs with an explicit safe request-field allowlist; never log task bodies."""
from datetime import datetime, timezone
import json
import logging

class JsonFormatter(logging.Formatter):
    def format(self, record):
        event = {'timestamp': datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
                 'level': record.levelname, 'logger': record.name, 'message': record.getMessage()}
        for key in ('request_id', 'method', 'path', 'status', 'duration_ms'):
            if hasattr(record, key):
                event[key] = getattr(record, key)
        if record.exc_info:
            event['exception'] = self.formatException(record.exc_info)
        return json.dumps(event, ensure_ascii=False)

def logging_config():
    return {'version':1, 'disable_existing_loggers':False,
            'formatters':{'json':{'()':'singverse_compute.core.logging.JsonFormatter'}},
            'handlers':{'console':{'class':'logging.StreamHandler','formatter':'json','stream':'ext://sys.stdout'}},
            'loggers':{'uvicorn':{'handlers':['console'],'level':'INFO','propagate':False},
                       'uvicorn.error':{'level':'INFO'}, 'uvicorn.access':{'handlers':[],'propagate':False},
                       'singverse_compute':{'handlers':['console'],'level':'INFO','propagate':False}}}
