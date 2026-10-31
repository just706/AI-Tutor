import json

import httpx
import pytest

from tutor_agent.adapters import JavaTools, JsonChatModel
from tutor_agent.ports import UpstreamError
from test_runner import request


async def test_model_posts_structured_output_request_and_preserves_usage():
    seen = []
    def handler(incoming):
        seen.append(incoming)
        payload = json.loads(incoming.content)
        assert payload["response_format"] == {"type": "json_object"}
        assert payload["max_tokens"] == 123
        assert payload["thinking"] == {"type": "disabled"}
        assert incoming.headers["authorization"] == "Bearer model-secret"
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": '{"action":"refuse"}'}}],
            "usage": {"prompt_tokens": 21, "completion_tokens": 9}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        completion = await JsonChatModel(client, "https://model.example", "model-secret", "test-model").complete(
            [{"role": "user", "content": "hi"}], max_tokens=123)
    assert len(seen) == 1 and str(seen[0].url) == "https://model.example/chat/completions"
    assert completion.prompt_tokens == 21 and completion.completion_tokens == 9


@pytest.mark.parametrize("status", [401, 429, 500, 302])
async def test_model_errors_and_redirects_never_retry_or_expose_provider_body(status):
    calls = []
    def handler(incoming):
        calls.append(incoming)
        return httpx.Response(status, text="private-provider-body model-secret", headers={"location": "https://other.example"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(UpstreamError) as error:
            await JsonChatModel(client, "https://model.example", "model-secret", "test-model").complete([], max_tokens=100)
    assert len(calls) == 1 and "model-secret" not in str(error.value) and "private-provider-body" not in str(error.value)


async def test_truncated_completion_and_oversized_body_are_rejected():
    for response in [httpx.Response(200, json={"choices": [{"finish_reason": "length", "message": {"content": "{}"}}]}),
                     httpx.Response(200, content=b"x" * 262145)]:
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: response)) as client:
            with pytest.raises(UpstreamError):
                await JsonChatModel(client, "https://model.example", "model-secret", "test").complete([], max_tokens=50)


async def test_java_search_carries_fixed_scope_outside_model_arguments():
    def handler(incoming):
        assert incoming.url.path == "/internal/agent/v1/search"
        assert incoming.headers["authorization"] == "Bearer bridge-secret"
        assert incoming.headers["x-tutor-scope"] == "private-scope-token"
        body = json.loads(incoming.content)
        assert body == {"request_id": "request-1", "attempt": 1, "user_id": 7, "conversation_id": 10,
            "document_ids": [11], "scope_version": "snapshot-1", "query": "导数", "limit": 4}
        return httpx.Response(200, json={"code": 200, "data": {"request_id": "request-1", "attempt": 1,
            "scope_version": "snapshot-1", "chunks": []}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await JavaTools(client, "http://127.0.0.1:8080", "bridge-secret").search(request(), "导数")
    assert result.chunks == []


async def test_java_http_200_business_error_is_not_treated_as_empty_evidence():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200,
            json={"code": 403, "message": "private database details", "data": None}))) as client:
        with pytest.raises(UpstreamError) as error:
            await JavaTools(client, "http://127.0.0.1:8080", "bridge-secret").read(request(), ["c1"])
    assert "private database" not in str(error.value)


def test_upstream_urls_reject_credentials_query_strings_and_remote_plain_http():
    for url in ["http://remote.example", "https://user:password@model.example", "https://model.example?key=secret", "file:///tmp"]:
        with pytest.raises(ValueError):
            JsonChatModel(None, url, "key", "test")


async def test_tool_envelope_supports_chinese_chunks_within_its_byte_limit():
    chunks = [{"id": f"c{index}", "document_id": 11, "chunk_index": index, "revision": "r1", "text": "字" * 3000}
              for index in range(8)]
    data = {"code": 200, "data": {"request_id": "request-1", "attempt": 1, "scope_version": "snapshot-1", "chunks": chunks}}
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=data))) as client:
        result = await JavaTools(client, "http://127.0.0.1:8080", "bridge-secret").read(request(), ["c1"])
    assert len(result.chunks) == 8
