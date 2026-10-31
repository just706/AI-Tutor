import ipaddress
from urllib.parse import urlsplit

import httpx
from pydantic import ValidationError

from .contracts import ToolResult
from .ports import Completion, UpstreamError
from .runner import parse_json


def validate_base_url(value):
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Invalid upstream URL")
    if parsed.scheme == "http":
        try:
            loopback = ipaddress.ip_address(parsed.hostname).is_loopback
        except ValueError:
            loopback = parsed.hostname == "localhost"
        if not loopback:
            raise ValueError("Remote upstreams require HTTPS")
    return value.rstrip("/")


async def post_json(client, url, headers, payload):
    try:
        # 不跟随重定向，不重试；认证头只能发送到配置的固定上游。
        async with client.stream("POST", url, headers=headers, json=payload, follow_redirects=False) as response:
            if response.status_code != 200:
                raise UpstreamError("upstream_unavailable")
            content = bytearray()
            async for block in response.aiter_bytes():
                content.extend(block)
                if len(content) > 262144:
                    raise UpstreamError("upstream_response_too_large")
            # 传输信封允许大于单次模型内容，但同样拒绝重复JSON键。
            data = parse_json(content.decode("utf-8"), max_chars=262144)
            if not isinstance(data, dict):
                raise UpstreamError("invalid_upstream_response")
            return data
    except (httpx.HTTPError, ValueError, TypeError, RecursionError):
        raise UpstreamError("upstream_unavailable") from None


class JsonChatModel:
    def __init__(self, client, base_url, api_key, model):
        self.client, self.base_url, self.api_key, self.model = client, validate_base_url(base_url), api_key, model

    async def complete(self, messages, *, max_tokens):
        data = await post_json(self.client, self.base_url + "/chat/completions",
            {"Authorization": f"Bearer {self.api_key}"}, {"model": self.model, "messages": messages,
                "stream": False, "thinking": {"type": "disabled"}, "response_format": {"type": "json_object"},
                "max_tokens": max_tokens})
        try:
            choice = data["choices"][0]
            content = choice["message"]["content"]
            if choice["finish_reason"] != "stop" or not isinstance(content, str) or not content.strip():
                raise ValueError("Incomplete completion")
            usage = data.get("usage") or {}
            values = [usage.get("prompt_tokens"), usage.get("completion_tokens")]
            if any(value is not None and (type(value) is not int or value < 0) for value in values):
                raise ValueError("Invalid usage")
            return Completion(content, *values)
        except (KeyError, IndexError, TypeError, ValueError, AttributeError):
            raise UpstreamError("invalid_model_response") from None


class JavaTools:
    def __init__(self, client, base_url, service_token):
        self.client, self.base_url, self.service_token = client, validate_base_url(base_url), service_token

    async def search(self, scope, query):
        return await self._call("search", scope, {"query": query, "limit": 4})

    async def read(self, scope, anchor_ids):
        return await self._call("read", scope, {"anchor_ids": anchor_ids, "radius": 1, "limit": 8})

    async def _call(self, operation, scope, arguments):
        context = scope.model_dump(include={"request_id", "attempt", "user_id", "conversation_id", "document_ids", "scope_version"})
        data = await post_json(self.client, f"{self.base_url}/internal/agent/v1/{operation}",
            {"Authorization": f"Bearer {self.service_token}", "X-Tutor-Scope": scope.scope_token}, {**context, **arguments})
        if type(data.get("code")) is not int or data["code"] != 200:
            raise UpstreamError("java_tool_unavailable")
        try:
            return ToolResult.model_validate(data.get("data"))
        except ValidationError:
            raise UpstreamError("invalid_java_tool_response") from None
