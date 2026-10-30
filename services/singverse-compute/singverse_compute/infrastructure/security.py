"""File-backed internal credentials; no token values in diagnostics."""
import hmac
import os
import secrets
from pathlib import Path
from ..core.errors import ComputeError

class InternalAuth:
    def __init__(self, token_file: Path | None):
        self.token_file = token_file

    def initialize(self):
        if self.token_file is None:
            return
        self.token_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            with self.token_file.open('x') as output:
                if os.name == 'nt':
                    # Windows chmod does not set an ACL. Limit the new token to this SID and SYSTEM.
                    import csv
                    import io
                    import subprocess
                    identity = subprocess.run(['whoami','/user','/fo','csv','/nh'], check=True,
                                              capture_output=True, text=True, timeout=5)
                    sid = next(csv.reader(io.StringIO(identity.stdout)))[1]
                    if not sid.startswith('S-1-'):
                        raise ValueError('Cannot determine credential file owner')
                    subprocess.run(['icacls', str(self.token_file), '/inheritance:r', '/grant:r',
                                    '*'+sid+':F', '*S-1-5-18:F'], check=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
                else:
                    self.token_file.chmod(0o600)
                output.write(secrets.token_hex(32))
        except FileExistsError:
            pass

    def _token(self) -> str:
        if self.token_file is None:
            return ''
        try:
            with self.token_file.open() as source:
                return source.read(257).strip()
        except (OSError, UnicodeError):
            return ''

    @property
    def available(self) -> bool:
        return 32 <= len(self._token()) <= 256

    def authorize(self, authorization: str):
        token = self._token()
        if not 32 <= len(token) <= 256 or not hmac.compare_digest(authorization.encode('utf-8'), ('Bearer ' + token).encode('utf-8')):
            raise ComputeError(401, 'Unauthorized')
