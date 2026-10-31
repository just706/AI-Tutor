import asyncio
import json

import pytest

from tutor_agent.contracts import Chunk, RunRequest, ToolResult
from tutor_agent.ports import Completion, UpstreamError
from tutor_agent.runner import AgentRunner, Limits


def request(**changes):
    return RunRequest.model_validate(dict(request_id="request-1", user_id=7, conversation_id=10,
        document_ids=[11], scope_version="snapshot-1", scope_token="private-scope-token",
        question="导数是什么？") | changes)


def chunk(**changes):
    return Chunk.model_validate(dict(id="c1", document_id=11, chunk_index=0, revision="r1",
        text="导数表示函数的局部变化率。") | changes)


PLAN = {"action": "search", "questions": [{"id": 1, "question": "导数是什么？"}], "query": "导数 定义"}
DRAFT = {"action": "draft", "claims": [{"id": "claim-1", "text": "导数表示局部变化率。", "covers": [1],
    "citations": [{"chunk_id": "c1", "quote": "导数表示函数的局部变化率"}]}]}
REVIEW = {"claims": [{"claim_id": "claim-1", "verdict": "supported", "reason": "原文给出定义。"}], "coverage": "complete"}


class ScriptedModel:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.messages = []

    async def complete(self, messages, *, max_tokens):
        self.messages.append(messages)
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return Completion(response if isinstance(response, str) else json.dumps(response, ensure_ascii=False), 10, 5)


class FixtureTools:
    def __init__(self, chunks=None):
        self.chunks = [chunk()] if chunks is None else chunks
        self.queries = []
        self.anchors = []

    async def search(self, scope, query):
        self.queries.append(query)
        return ToolResult(request_id=scope.request_id, attempt=scope.attempt, scope_version=scope.scope_version, chunks=self.chunks)

    async def read(self, scope, anchor_ids):
        self.anchors.append(anchor_ids)
        return ToolResult(request_id=scope.request_id, attempt=scope.attempt, scope_version=scope.scope_version, chunks=self.chunks)


async def test_answer_requires_exact_quotes_full_coverage_and_review():
    model, tools = ScriptedModel(PLAN, DRAFT, REVIEW), FixtureTools()
    result = await AgentRunner(model, tools).run(request())
    assert result.status == "answered"
    assert result.answer == "导数表示局部变化率。[1]"
    assert result.model_calls == 3 and result.searches == 1
    assert result.prompt_tokens == 30 and result.completion_tokens == 15
    assert result.sources[0].text == "导数表示函数的局部变化率。"
    assert result.semantic_acceptance == "not_evaluated"
    assert "private-scope-token" not in json.dumps(model.messages)


async def test_unknown_review_refuses_to_publish_the_draft():
    review = {"claims": [{"claim_id": "claim-1", "verdict": "unknown", "reason": "不能确认关系。"}], "coverage": "complete"}
    result = await AgentRunner(ScriptedModel(PLAN, DRAFT, review), FixtureTools()).run(request())
    assert result.status == "insufficient" and result.answer is None and result.claims == []


async def test_answer_citation_count_stays_within_java_history_contract():
    evidence = [chunk(id=f"c{index}", chunk_index=index) for index in range(9)]
    class NeighborTools(FixtureTools):
        async def read(self, scope, anchor_ids):
            return ToolResult(request_id=scope.request_id, attempt=scope.attempt,
                scope_version=scope.scope_version, chunks=[evidence[-1]])
    draft = {"action": "draft", "claims": [{"id": f"claim-{index}", "text": "导数表示局部变化率。", "covers": [1],
        "citations": [{"chunk_id": item.id, "quote": "导数表示函数的局部变化率"} for item in group]}
        for index, group in enumerate([evidence[:5], evidence[5:]])]}
    review = {"claims": [{"claim_id": claim["id"], "verdict": "supported", "reason": "固定测试结果"}
                         for claim in draft["claims"]], "coverage": "complete"}
    result = await AgentRunner(ScriptedModel(PLAN, {"action": "read", "anchor_ids": ["c7"]}, draft, review),
                               NeighborTools(evidence[:8])).run(request())
    assert result.status == "invalid_evidence" and result.model_calls == 3


@pytest.mark.parametrize("mutation", ["invent_quote", "unknown_source", "missing_question"])
async def test_unverified_draft_is_rejected_before_spending_a_review_call(mutation):
    draft = json.loads(json.dumps(DRAFT))
    if mutation == "invent_quote":
        draft["claims"][0]["citations"][0]["quote"] = "资料中没有这句话"
    elif mutation == "unknown_source":
        draft["claims"][0]["citations"][0]["chunk_id"] = "c999"
    else:
        draft["claims"][0]["covers"] = [2]
    result = await AgentRunner(ScriptedModel(PLAN, draft), FixtureTools()).run(request())
    assert result.status == "invalid_evidence" and result.model_calls == 2


async def test_query_rewrite_has_one_extra_search_and_reserves_final_review():
    model = ScriptedModel(PLAN, {"action": "search", "query": "函数 局部变化率"}, DRAFT, REVIEW)
    tools = FixtureTools()
    result = await AgentRunner(model, tools).run(request())
    assert result.status == "answered" and result.model_calls == 4 and result.searches == 2
    assert tools.queries == ["导数 定义", "函数 局部变化率"]


async def test_repeated_search_stops_without_a_duplicate_tool_call():
    tools = FixtureTools()
    result = await AgentRunner(ScriptedModel(PLAN, {"action": "search", "query": "导数  定义"}), tools).run(request())
    assert result.status == "budget_exhausted" and len(tools.queries) == 1
    assert result.stop_reason == "repeated_query"


async def test_last_decision_is_told_which_tools_and_queries_are_no_longer_available():
    model = ScriptedModel(PLAN, {"action": "search", "query": "函数 局部变化率"}, DRAFT, REVIEW)
    result = await AgentRunner(model, FixtureTools()).run(request())
    assert result.status == "answered"
    decision = json.loads(model.messages[2][-1]["content"])
    assert decision["previous_queries"] == ["导数 定义", "函数 局部变化率"]
    assert decision["allowed_actions"] == ["draft", "clarify", "refuse"]


async def test_search_after_final_decision_reports_review_reservation_not_global_exhaustion():
    model = ScriptedModel(PLAN, {"action": "search", "query": "局部变化率"}, {"action": "read", "anchor_ids": ["c1"]})
    result = await AgentRunner(model, FixtureTools()).run(request())
    assert result.status == "budget_exhausted" and result.model_calls == 3 and result.reads == 0
    assert result.stop_reason == "review_reserved"


async def test_no_model_budget_means_no_tool_or_model_activity():
    model, tools = ScriptedModel(), FixtureTools()
    result = await AgentRunner(model, tools, Limits(model_calls=0)).run(request())
    assert result.status == "budget_exhausted" and result.model_calls == 0
    assert model.messages == [] and tools.queries == []


async def test_call_cap_never_publishes_an_unreviewed_draft():
    result = await AgentRunner(ScriptedModel(PLAN, DRAFT), FixtureTools(), Limits(model_calls=2)).run(request())
    assert result.status == "budget_exhausted" and result.answer is None and result.model_calls <= 2


async def test_invalid_action_or_json_is_terminal_and_does_not_retry():
    for response in [{"action": "execute_sql", "sql": "drop table"}, '{"action":"search","action":"clarify"}']:
        model = ScriptedModel(response)
        result = await AgentRunner(model, FixtureTools()).run(request())
        assert result.status == "invalid_output" and result.model_calls == 1


async def test_failure_attempt_is_counted_and_exception_text_is_not_exposed():
    result = await AgentRunner(ScriptedModel(UpstreamError("secret-key-provider-response")), FixtureTools()).run(request())
    assert result.status == "upstream_error" and result.model_calls == 1
    assert "secret-key" not in result.model_dump_json()
    assert result.usage_complete is False


async def test_model_timeout_cancels_call_and_prevents_later_tools():
    cancelled = asyncio.Event()
    class HangingModel:
        async def complete(self, *args, **kwargs):
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
    tools = FixtureTools()
    result = await AgentRunner(HangingModel(), tools, Limits(model_seconds=0.02)).run(request())
    assert result.status == "timeout" and result.model_calls == 1
    assert cancelled.is_set() and tools.queries == []


async def test_tool_cannot_return_a_different_document_or_scope_version():
    bad = Chunk(id="c2", document_id=99, chunk_index=0, revision="r1", text="other user's data")
    result = await AgentRunner(ScriptedModel(PLAN), FixtureTools([bad])).run(request())
    assert result.status == "invalid_evidence"
    assert "other user's data" not in result.model_dump_json()


async def test_read_only_accepts_previously_retrieved_anchor_ids():
    tools = FixtureTools()
    result = await AgentRunner(ScriptedModel(PLAN, {"action": "read", "anchor_ids": ["unknown"]}), tools).run(request())
    assert result.status == "invalid_evidence" and tools.anchors == []


async def test_clarification_does_not_retrieve_or_make_further_calls():
    model = ScriptedModel({"action": "clarify", "questions": [], "clarification": "你指的是导数还是积分？"})
    tools = FixtureTools()
    result = await AgentRunner(model, tools).run(request())
    assert result.status == "clarification" and result.model_calls == 1 and tools.queries == []


async def test_bounded_read_can_add_a_neighbor_before_a_reviewed_answer():
    neighbor = Chunk(id="c2", document_id=11, chunk_index=1, revision="r1", text="导数表示函数的局部变化率。")
    class NeighborTools(FixtureTools):
        async def read(self, scope, anchors):
            self.chunks = [neighbor]
            return await super().read(scope, anchors)
    draft = json.loads(json.dumps(DRAFT))
    draft["claims"][0]["citations"][0]["chunk_id"] = "c2"
    tools = NeighborTools()
    result = await AgentRunner(ScriptedModel(PLAN, {"action": "read", "anchor_ids": ["c1"]}, draft, REVIEW), tools).run(request())
    assert result.status == "answered" and result.reads == 1 and result.model_calls == 4
    assert result.sources[0].id == "c2"


@pytest.mark.parametrize("change", ["revision", "text", "distant_neighbor", "scope", "attempt"])
async def test_changed_snapshot_or_out_of_range_neighbor_is_rejected(change):
    class ChangedTools(FixtureTools):
        async def read(self, scope, anchor_ids):
            record = chunk().model_dump()
            if change == "revision": record["revision"] = "r2"
            if change == "text": record["text"] = "changed text"
            if change == "distant_neighbor": record.update(id="c9", chunk_index=20)
            return ToolResult(request_id=scope.request_id, attempt=2 if change == "attempt" else scope.attempt,
                scope_version="other-scope" if change == "scope" else scope.scope_version,
                chunks=[Chunk.model_validate(record)])
    result = await AgentRunner(ScriptedModel(PLAN, {"action": "read", "anchor_ids": ["c1"]}), ChangedTools()).run(request())
    assert result.status == "invalid_evidence" and result.model_calls == 2 and result.answer is None


async def test_evidence_size_cap_stops_before_sending_large_context_to_model():
    model = ScriptedModel(PLAN)
    result = await AgentRunner(model, FixtureTools(), Limits(evidence_chars=3)).run(request())
    assert result.status == "budget_exhausted" and result.model_calls == 1


async def test_total_deadline_cancels_a_hanging_tool():
    cancelled = asyncio.Event()
    class HangingTools(FixtureTools):
        async def search(self, scope, query):
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
    result = await AgentRunner(ScriptedModel(PLAN), HangingTools(), Limits(total_seconds=0.03, tool_seconds=5)).run(request())
    assert result.status == "timeout" and result.searches == 1 and cancelled.is_set()


async def test_incomplete_or_duplicate_final_review_is_not_an_approval():
    for review in [dict(REVIEW, coverage="unknown"), dict(REVIEW, claims=[REVIEW["claims"][0]] * 2)]:
        result = await AgentRunner(ScriptedModel(PLAN, DRAFT, review), FixtureTools()).run(request())
        assert result.status in {"insufficient", "invalid_output"} and result.answer is None


async def test_same_document_position_cannot_be_replaced_under_a_different_chunk_id():
    class ReidentifiedTools(FixtureTools):
        async def read(self, scope, anchor_ids):
            return ToolResult(request_id=scope.request_id, attempt=scope.attempt, scope_version=scope.scope_version,
                chunks=[Chunk(id="other-id", document_id=11, chunk_index=0, revision="r1", text="changed under new ID")])
    model = ScriptedModel(PLAN, {"action": "read", "anchor_ids": ["c1"]}, {"action": "refuse", "missing": "missing"})
    result = await AgentRunner(model, ReidentifiedTools()).run(request())
    assert result.status == "invalid_evidence" and result.model_calls == 2
