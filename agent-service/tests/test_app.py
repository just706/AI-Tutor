import asyncio

import httpx
import pytest

from tutor_agent.app import create_app
from tutor_agent.runner import AgentRunner
from tutor_agent.demo import DemoModel, DemoTools, demo_request
from tutor_agent.ports import UpstreamError
from test_runner import ScriptedModel, FixtureTools, request, PLAN, DRAFT, REVIEW

TOKEN = "local-test-service-token-32-characters"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}


async def test_authentication_precedes_work_and_validation_does_not_echo_secrets():
    model = ScriptedModel(PLAN, DRAFT, REVIEW)
    app = create_app(AgentRunner(model, FixtureTools()), service_token=TOKEN)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        denied = await client.post("/v1/runs", json=request().model_dump())
        assert denied.status_code == 401 and model.messages == []
        invalid = await client.post("/v1/runs", headers=HEADERS, json={"scope_token": "never-echo-this"})
        assert invalid.status_code == 422 and "never-echo-this" not in invalid.text
        large = await client.post("/v1/runs", headers=HEADERS, content=b"x" * 65537)
        assert large.status_code == 413


async def test_completed_request_is_reused_and_changed_question_conflicts():
    model = ScriptedModel(PLAN, DRAFT, REVIEW)
    app = create_app(AgentRunner(model, FixtureTools()), service_token=TOKEN)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        payload = request().model_dump()
        first = await client.post("/v1/runs", json=payload, headers=HEADERS)
        second = await client.post("/v1/runs", json=payload, headers=HEADERS)
        payload["question"] = "different question"
        conflict = await client.post("/v1/runs", json=payload, headers=HEADERS)
    assert first.status_code == 200 and second.json() == first.json()
    assert len(model.messages) == 3 and conflict.status_code == 409


async def test_concurrent_duplicate_requests_share_one_execution():
    started, release = asyncio.Event(), asyncio.Event()
    class DelayedModel(ScriptedModel):
        async def complete(self, *args, **kwargs):
            if not self.messages:
                started.set()
                await release.wait()
            return await super().complete(*args, **kwargs)
    model = DelayedModel(PLAN, DRAFT, REVIEW)
    app = create_app(AgentRunner(model, FixtureTools()), service_token=TOKEN)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        first = asyncio.create_task(client.post("/v1/runs", json=request().model_dump(), headers=HEADERS))
        await asyncio.wait_for(started.wait(), 1)
        second = asyncio.create_task(client.post("/v1/runs", json=request().model_dump(), headers=HEADERS))
        release.set()
        responses = await asyncio.gather(first, second)
    assert all(response.status_code == 200 for response in responses)
    assert responses[0].json() == responses[1].json() and len(model.messages) == 3


async def test_full_ledger_refuses_new_work_without_evicting_deduplication():
    model = ScriptedModel(PLAN, DRAFT, REVIEW)
    app = create_app(AgentRunner(model, FixtureTools()), service_token=TOKEN, max_entries=1)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        payload = request().model_dump()
        first = await client.post("/v1/runs", json=payload, headers=HEADERS)
        other = dict(payload, request_id="request-2")
        full = await client.post("/v1/runs", json=other, headers=HEADERS)
        cached = await client.post("/v1/runs", json=payload, headers=HEADERS)
    assert full.status_code == 503 and cached.json() == first.json() and len(model.messages) == 3


@pytest.mark.parametrize("scenario,status,model_calls", [
    ("answer", "answered", 3), ("rewrite", "answered", 4), ("read", "answered", 4),
    ("refuse", "insufficient", 2), ("clarify", "clarification", 1),
])
async def test_demo_scenarios_use_the_real_http_contract_and_runner(scenario, status, model_calls):
    app = create_app(AgentRunner(DemoModel(), DemoTools()), service_token=TOKEN)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/v1/runs", json=demo_request(scenario).model_dump(), headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["status"] == status and response.json()["model_calls"] == model_calls


async def test_upstream_failure_is_cached_instead_of_implicitly_spending_more_calls():
    model = ScriptedModel(UpstreamError("secret"))
    app = create_app(AgentRunner(model, FixtureTools()), service_token=TOKEN)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        first = await client.post("/v1/runs", json=request().model_dump(), headers=HEADERS)
        again = await client.post("/v1/runs", json=request().model_dump(), headers=HEADERS)
    assert first.json()["status"] == "upstream_error" and first.json() == again.json() and len(model.messages) == 1


async def test_concurrency_cap_rejects_new_keys_while_allowing_existing_run_to_finish():
    started, release = asyncio.Event(), asyncio.Event()
    class WaitingModel(ScriptedModel):
        async def complete(self, *args, **kwargs):
            if not self.messages:
                started.set()
                await release.wait()
            return await super().complete(*args, **kwargs)
    app = create_app(AgentRunner(WaitingModel(PLAN, DRAFT, REVIEW), FixtureTools()), service_token=TOKEN, max_concurrent=1)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        pending = asyncio.create_task(client.post("/v1/runs", json=request().model_dump(), headers=HEADERS))
        await asyncio.wait_for(started.wait(), 1)
        rejected = await client.post("/v1/runs", json=dict(request().model_dump(), request_id="other"), headers=HEADERS)
        assert rejected.status_code == 503
        release.set()
        assert (await pending).json()["status"] == "answered"


def test_real_model_requires_explicit_enable_and_credentials(monkeypatch):
    from tutor_agent.app import build_app
    monkeypatch.setenv("AGENT_MODE", "live")
    monkeypatch.setenv("AGENT_SERVICE_TOKEN", TOKEN)
    monkeypatch.delenv("AGENT_ENABLE_REAL_MODEL", raising=False)
    with pytest.raises(ValueError, match="explicit"):
        build_app()


def test_api_request_cannot_override_model_tool_urls_or_call_budgets():
    from pydantic import ValidationError
    from tutor_agent.contracts import RunRequest
    for field in ["model_url", "tool_url", "max_model_calls"]:
        with pytest.raises(ValidationError):
            RunRequest.model_validate({**request().model_dump(), field: "untrusted"})
