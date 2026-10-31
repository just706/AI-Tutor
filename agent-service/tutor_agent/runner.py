import asyncio
from dataclasses import dataclass, field
import json
import time
import unicodedata

from pydantic import TypeAdapter, ValidationError

from .contracts import (Chunk, Clarify, Decision, Draft, Plan, Read, Refuse, Review,
                        RunRequest, RunResult, Search, TraceEvent)
from .ports import UpstreamError
from .prompts import messages


@dataclass(frozen=True)
class Limits:
    model_calls: int = 4
    searches: int = 2
    reads: int = 1
    total_seconds: float = 45
    model_seconds: float = 15
    tool_seconds: float = 5
    max_output_tokens: int = 2048
    evidence_chars: int = 20000

    def __post_init__(self):
        if not (0 <= self.model_calls <= 4 and 0 <= self.searches <= 2 and 0 <= self.reads <= 1):
            raise ValueError("Call limits exceed prototype caps")
        if not all(0 < value <= 120 for value in [self.total_seconds, self.model_seconds, self.tool_seconds]):
            raise ValueError("Invalid deadline")
        if not 1 <= self.max_output_tokens <= 4096 or not 1 <= self.evidence_chars <= 20000:
            raise ValueError("Invalid content limit")


class StopRun(Exception):
    def __init__(self, status, reason=None):
        self.status = status
        self.reason = reason


@dataclass
class State:
    model_calls: int = 0
    searches: int = 0
    reads: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    usage_complete: bool = True
    trace: list = field(default_factory=list)
    chunks: dict[str, Chunk] = field(default_factory=dict)
    queries: set[str] = field(default_factory=set)
    query_history: list[str] = field(default_factory=list)


def parse_json(content, *, max_chars=60000):
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result

    if not isinstance(content, str) or len(content) > max_chars:
        raise ValueError("Invalid model output length")
    return json.loads(content, object_pairs_hook=unique_pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON")))


MESSAGES = {
    "answered": "已完成教材依据检查；模型判断仍可能有误。",
    "insufficient": "当前教材证据不足以完整回答，请补充相关资料或明确问题。",
    "budget_exhausted": "本次检索或模型调用已达到上限，尚未形成经过复核的回答。",
    "timeout": "本次处理超时，未保存未经复核的回答。",
    "upstream_error": "模型或教材工具暂时不可用，本次处理已停止。",
    "invalid_output": "模型返回格式不符合约定，本次处理已停止。",
    "invalid_evidence": "引用或教材快照无法核对，本次处理已停止。",
}


class AgentRunner:
    def __init__(self, model, tools, limits=Limits()):
        self.model, self.tools, self.limits = model, tools, limits

    async def run(self, request: RunRequest) -> RunResult:
        state = State()
        try:
            async with asyncio.timeout(self.limits.total_seconds):
                return await self._run(request, state)
        except StopRun as stopped:
            return self._result(request, state, stopped.status, stop_reason=stopped.reason)
        except TimeoutError:
            return self._result(request, state, "timeout")
        except UpstreamError:
            return self._result(request, state, "upstream_error")

    def _result(self, request, state, status, *, message=None, draft=None, stop_reason=None):
        answer, sources = None, []
        if draft is not None:
            used = list(dict.fromkeys(citation.chunk_id for claim in draft.claims for citation in claim.citations))
            sources = [state.chunks[key] for key in used]
            numbers = {key: index + 1 for index, key in enumerate(used)}
            answer = "\n\n".join(claim.text + "".join(f"[{numbers[key]}]" for key in
                dict.fromkeys(citation.chunk_id for citation in claim.citations)) for claim in draft.claims)
        return RunResult(request_id=request.request_id, attempt=request.attempt, status=status,
            message=message or MESSAGES[status], stop_reason=stop_reason, answer=answer, claims=draft.claims if draft else [], sources=sources,
            model_calls=state.model_calls, searches=state.searches, reads=state.reads,
            prompt_tokens=state.prompt_tokens, completion_tokens=state.completion_tokens,
            usage_complete=state.usage_complete, trace=state.trace)

    async def _model(self, stage, payload, schema, state, *, reserve=0):
        if state.model_calls >= self.limits.model_calls - reserve:
            raise StopRun("budget_exhausted")
        # 先计数再发送；传输失败、超时和非法输出都消耗本次调用额度。
        state.model_calls += 1
        previously_complete = state.usage_complete
        state.usage_complete = False
        started, outcome = time.monotonic(), "failed"
        try:
            response = await asyncio.wait_for(self.model.complete(messages(stage, payload),
                max_tokens=self.limits.max_output_tokens), timeout=self.limits.model_seconds)
            for value in [response.prompt_tokens, response.completion_tokens]:
                if value is not None and (type(value) is not int or value < 0):
                    raise StopRun("invalid_output")
            state.prompt_tokens += response.prompt_tokens or 0
            state.completion_tokens += response.completion_tokens or 0
            state.usage_complete = previously_complete and response.prompt_tokens is not None and response.completion_tokens is not None
            try:
                value = TypeAdapter(schema).validate_python(parse_json(response.content))
            except (ValueError, ValidationError, TypeError, RecursionError):
                raise StopRun("invalid_output") from None
            outcome = "received"
            return value
        finally:
            state.trace.append(TraceEvent(step=len(state.trace) + 1, operation=f"model.{stage}", outcome=outcome,
                elapsed_ms=round((time.monotonic() - started) * 1000, 3)))

    async def _tool(self, action, request, state):
        if isinstance(action, Search):
            query_key = " ".join(unicodedata.normalize("NFKC", action.query).lower().split())
            if query_key in state.queries:
                raise StopRun("budget_exhausted", "repeated_query")
            if state.searches >= self.limits.searches:
                raise StopRun("budget_exhausted", "search_limit")
            state.queries.add(query_key)
            state.query_history.append(action.query)
            state.searches += 1
            operation = "tool.search"
            call = self.tools.search(request, action.query)
        else:
            if any(key not in state.chunks for key in action.anchor_ids) or len(set(action.anchor_ids)) != len(action.anchor_ids):
                raise StopRun("invalid_evidence")
            if state.reads >= self.limits.reads:
                raise StopRun("budget_exhausted", "read_limit")
            state.reads += 1
            operation = "tool.read"
            call = self.tools.read(request, action.anchor_ids)
        started, outcome = time.monotonic(), "failed"
        try:
            result = await asyncio.wait_for(call, timeout=self.limits.tool_seconds)
            if (result.request_id, result.attempt, result.scope_version) != (request.request_id, request.attempt, request.scope_version):
                raise StopRun("invalid_evidence")
            if len({chunk.id for chunk in result.chunks}) != len(result.chunks):
                raise StopRun("invalid_evidence")
            merged = dict(state.chunks)
            for chunk in result.chunks:
                if chunk.document_id not in request.document_ids:
                    raise StopRun("invalid_evidence")
                if chunk.id in merged and chunk != merged[chunk.id]:
                    raise StopRun("invalid_evidence")
                if any(old.document_id == chunk.document_id and old.revision != chunk.revision for old in merged.values()):
                    raise StopRun("invalid_evidence")
                if any(old.document_id == chunk.document_id and old.chunk_index == chunk.chunk_index
                       and old != chunk for old in merged.values()):
                    raise StopRun("invalid_evidence")
                if isinstance(action, Read) and not any(
                    chunk.document_id == state.chunks[key].document_id
                    and chunk.revision == state.chunks[key].revision
                    and abs(chunk.chunk_index - state.chunks[key].chunk_index) <= 1 for key in action.anchor_ids):
                    raise StopRun("invalid_evidence")
                merged[chunk.id] = chunk
            if len(merged) > 16 or sum(len(chunk.text) for chunk in merged.values()) > self.limits.evidence_chars:
                raise StopRun("budget_exhausted")
            state.chunks = merged
            outcome = "received"
        finally:
            state.trace.append(TraceEvent(step=len(state.trace) + 1, operation=operation, outcome=outcome,
                elapsed_ms=round((time.monotonic() - started) * 1000, 3)))

    @staticmethod
    def _validate_draft(draft, plan, state):
        # Java历史引用最多8段；在花费最终复核额度之前拒绝不兼容的草稿。
        if len({citation.chunk_id for claim in draft.claims for citation in claim.citations}) > 8:
            raise StopRun("invalid_evidence")
        if len({claim.id for claim in draft.claims}) != len(draft.claims):
            raise StopRun("invalid_evidence")
        required = {item.id for item in plan.questions}
        covered = set()
        for claim in draft.claims:
            if len(set(claim.covers)) != len(claim.covers) or not set(claim.covers) <= required:
                raise StopRun("invalid_evidence")
            covered.update(claim.covers)
            for citation in claim.citations:
                if citation.chunk_id not in state.chunks or not citation.quote.strip() or citation.quote not in state.chunks[citation.chunk_id].text:
                    raise StopRun("invalid_evidence")
        if covered != required:
            raise StopRun("invalid_evidence")

    async def _run(self, request, state):
        payload = {"question": request.question, "recent_user_questions": request.recent_user_questions}
        plan = await self._model("plan", payload, Plan, state)
        if plan.action == "clarify":
            return self._result(request, state, "clarification", message=plan.clarification)
        payload["questions"] = [item.model_dump() for item in plan.questions]
        await self._tool(Search(action="search", query=plan.query), request, state)
        while True:
            payload["evidence"] = [chunk.model_dump() for chunk in state.chunks.values()]
            payload["previous_queries"] = list(state.query_history)
            allowed = ["draft", "clarify", "refuse"]
            # 本次决策之后还需一次决策和一次复核，才能选择额外工具动作。
            if state.model_calls < self.limits.model_calls - 2:
                if state.searches < self.limits.searches:
                    allowed.append("search")
                if state.chunks and state.reads < self.limits.reads:
                    allowed.append("read")
            payload["allowed_actions"] = allowed
            payload["remaining"] = {"model_calls": self.limits.model_calls - state.model_calls,
                "searches": self.limits.searches - state.searches, "reads": self.limits.reads - state.reads,
                "reserve_final_review": 1}
            # 至少为最终复核保留一次模型调用；预算不够时不能直接发布草稿。
            action = await self._model("decide", payload, Decision, state, reserve=1)
            if isinstance(action, (Search, Read)):
                if state.model_calls >= self.limits.model_calls - 1:
                    raise StopRun("budget_exhausted", "review_reserved")
                await self._tool(action, request, state)
            elif isinstance(action, Clarify):
                return self._result(request, state, "clarification", message=action.question)
            elif isinstance(action, Refuse):
                return self._result(request, state, "insufficient")
            else:
                self._validate_draft(action, plan, state)
                review = await self._model("review", {**payload, "draft": action.model_dump()}, Review, state)
                ids = [item.claim_id for item in review.claims]
                if len(set(ids)) != len(ids) or set(ids) != {claim.id for claim in action.claims}:
                    raise StopRun("invalid_output")
                if review.coverage != "complete" or any(item.verdict != "supported" for item in review.claims):
                    return self._result(request, state, "insufficient")
                return self._result(request, state, "answered", draft=action)
