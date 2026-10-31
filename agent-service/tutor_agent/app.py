import asyncio
from contextlib import asynccontextmanager
import hashlib
import hmac
import json
import os

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .adapters import JavaTools, JsonChatModel
from .contracts import RunRequest, RunResult
from .demo import DemoModel, DemoTools
from .runner import AgentRunner


class RequestBoundary:
    def __init__(self, app, token):
        self.app, self.token = app, token

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        if scope["path"] == "/health" and scope["method"] == "GET":
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers", []))
        expected = ("Bearer " + self.token).encode("ascii")
        if not hmac.compare_digest(headers.get(b"authorization", b""), expected):
            return await JSONResponse({"error": "unauthorized"}, status_code=401)(scope, receive, send)
        buffered, size = [], 0
        while True:
            event = await receive()
            if event["type"] == "http.disconnect":
                return
            size += len(event.get("body", b""))
            if size > 65536:
                return await JSONResponse({"error": "request_too_large"}, status_code=413)(scope, receive, send)
            buffered.append(event)
            if not event.get("more_body", False):
                break
        async def replay():
            return buffered.pop(0) if buffered else await receive()
        return await self.app(scope, replay, send)


class RunLedger:
    """Single-process deduplication. No eviction or silent re-execution of reserved keys."""
    def __init__(self, runner, max_entries, max_concurrent):
        self.runner, self.max_entries, self.max_concurrent = runner, max_entries, max_concurrent
        self.entries, self.active = {}, 0
        self.lock = asyncio.Lock()

    async def run(self, request):
        key = (request.user_id, request.request_id, request.attempt)
        fingerprint = hashlib.sha256(json.dumps(request.model_dump(), ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        async with self.lock:
            if key in self.entries:
                previous, task = self.entries[key]
                if not hmac.compare_digest(previous, fingerprint):
                    raise HTTPException(409, "request_conflict")
            else:
                if len(self.entries) >= self.max_entries or self.active >= self.max_concurrent:
                    raise HTTPException(503, "agent_capacity_reached")
                self.active += 1
                task = asyncio.create_task(self._execute(request))
                self.entries[key] = (fingerprint, task)
        # 某个HTTP等待者断开不会取消共享执行；runner的总截止时间仍会停止它。
        return await asyncio.shield(task)

    async def _execute(self, request):
        try:
            return await self.runner.run(request)
        finally:
            async with self.lock:
                self.active -= 1

    async def close(self):
        tasks = [task for _, task in self.entries.values()]
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


def create_app(runner, *, service_token, mode="demo", max_entries=128, max_concurrent=2):
    if not service_token or len(service_token) < 24 or not service_token.isascii() or any(char.isspace() for char in service_token):
        raise ValueError("Set an ASCII AGENT_SERVICE_TOKEN with at least 24 non-whitespace characters")
    if mode not in {"demo", "live"} or max_entries < 1 or max_concurrent < 1:
        raise ValueError("Invalid service configuration")
    ledger = RunLedger(runner, max_entries, max_concurrent)
    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            await ledger.close()
            for client in getattr(app.state, "outbound_clients", []):
                await client.aclose()
    app = FastAPI(title="AI Tutor Python Agent Prototype", version="0.1.0", lifespan=lifespan)
    app.add_middleware(RequestBoundary, token=service_token)
    app.state.ledger = ledger

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request, _error):
        return JSONResponse({"error": "invalid_request"}, status_code=422)

    @app.get("/health")
    async def health():
        return {"status": "ok", "mode": mode, "version": "1", "production_ready": False,
                "java_integration": "available" if mode == "live" else "demo_only"}

    @app.post("/v1/runs", response_model=RunResult)
    async def run(body: RunRequest):
        return await ledger.run(body)

    return app


def build_app():
    """Uvicorn factory. Read environment only at startup, never from API payloads."""
    token = os.environ.get("AGENT_SERVICE_TOKEN", "")
    mode = os.environ.get("AGENT_MODE", "demo")
    if mode == "demo":
        return create_app(AgentRunner(DemoModel(), DemoTools()), service_token=token, mode=mode)
    if mode != "live" or os.environ.get("AGENT_ENABLE_REAL_MODEL") != "true":
        raise ValueError("Live mode requires explicit AGENT_ENABLE_REAL_MODEL=true")
    api_key = os.environ.get("AGENT_MODEL_API_KEY", "")
    bridge_token = os.environ.get("AGENT_JAVA_SERVICE_TOKEN", "")
    if not api_key or not bridge_token:
        raise ValueError("Configure model and Java service credentials for live mode")
    client = httpx.AsyncClient(timeout=15, follow_redirects=False, trust_env=False)
    model = JsonChatModel(client, os.environ.get("AGENT_MODEL_BASE_URL", "https://api.deepseek.com"), api_key,
                         os.environ.get("AGENT_MODEL_NAME", "deepseek-v4-flash"))
    tools = JavaTools(client, os.environ.get("AGENT_JAVA_BASE_URL", "http://127.0.0.1:8080"), bridge_token)
    app = create_app(AgentRunner(model, tools), service_token=token, mode=mode)
    app.state.outbound_clients = [client]
    return app
