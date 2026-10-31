from dataclasses import dataclass
from typing import Protocol

from .contracts import RunRequest, ToolResult


class UpstreamError(Exception):
    """A deliberately sanitized upstream failure."""


@dataclass(frozen=True)
class Completion:
    content: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None


class Model(Protocol):
    async def complete(self, messages: list[dict[str, str]], *, max_tokens: int) -> Completion: ...


class Tools(Protocol):
    async def search(self, scope: RunRequest, query: str) -> ToolResult: ...
    async def read(self, scope: RunRequest, anchor_ids: list[str]) -> ToolResult: ...
