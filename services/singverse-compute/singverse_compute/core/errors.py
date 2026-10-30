"""Application failures independent of HTTP/framework implementations."""
class ComputeError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message
