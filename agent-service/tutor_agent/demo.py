"""Scripted demo only: exercises the real runner without any model or Java calls."""
import argparse
import asyncio
import json

from .contracts import Chunk, RunRequest, ToolResult
from .ports import Completion, UpstreamError
from .runner import AgentRunner

SCENARIOS = {
    "answer": "导数是什么？",
    "rewrite": "函数局部变化快慢叫什么？",
    "read": "蓝桥编码的发明者是谁？",
    "refuse": "这份调查的总体方差是多少？",
    "clarify": "它是什么？",
}

CHUNKS = [
    Chunk(id="demo:0", document_id=11, chunk_index=0, revision="demo-r1", text="导数表示函数的局部变化率。"),
    Chunk(id="demo:1", document_id=11, chunk_index=1, revision="demo-r1", text="蓝桥编码是本演示中的合成规则，把编号映射为颜色。"),
    Chunk(id="demo:2", document_id=11, chunk_index=2, revision="demo-r1", text="在这个合成演示中，蓝桥编码由陈雨发明。"),
    Chunk(id="demo:3", document_id=11, chunk_index=3, revision="demo-r1", text="总体方差衡量数据的离散程度。本材料未提供调查数据。"),
]


def demo_request(scenario="answer"):
    return RunRequest(request_id=f"demo-{scenario}", user_id=7, conversation_id=10, document_ids=[11],
        scope_version="demo-v1", scope_token="local-demo", question=SCENARIOS[scenario])


class DemoTools:
    @staticmethod
    def _result(scope, chunks):
        if (scope.user_id, scope.conversation_id, scope.document_ids, scope.scope_version, scope.scope_token) != (7, 10, [11], "demo-v1", "local-demo"):
            raise UpstreamError("demo_scope_invalid")
        return ToolResult(request_id=scope.request_id, attempt=scope.attempt, scope_version=scope.scope_version, chunks=chunks)

    async def search(self, scope, query):
        selected = [CHUNKS[0]] if "导数" in query else [CHUNKS[1]] if "蓝桥" in query else [CHUNKS[3]] if "方差" in query else []
        return self._result(scope, selected)

    async def read(self, scope, anchor_ids):
        anchors = [chunk for chunk in CHUNKS if chunk.id in anchor_ids]
        if len(anchors) != len(anchor_ids):
            raise UpstreamError("unknown_demo_anchor")
        return self._result(scope, [chunk for chunk in CHUNKS if any(abs(chunk.chunk_index - anchor.chunk_index) <= 1 for anchor in anchors)])


class DemoModel:
    async def complete(self, messages, *, max_tokens):
        state = json.loads(messages[-1]["content"])
        question, stage = state["question"], state["stage"]
        if stage == "plan":
            if question not in SCENARIOS.values() or question == SCENARIOS["clarify"]:
                decision = {"action": "clarify", "questions": [], "clarification": "演示仅支持内置案例；你指的是哪个概念？"}
            else:
                query = {"answer": "导数", "rewrite": "局部变化快慢", "read": "蓝桥编码", "refuse": "总体方差"}[
                    next(name for name, value in SCENARIOS.items() if value == question)]
                decision = {"action": "search", "questions": [{"id": 1, "question": question}], "query": query}
        elif stage == "review":
            # 固定演示判决用于验证流程，不是模型语义验证结果。
            decision = {"claims": [{"claim_id": claim["id"], "verdict": "supported", "reason": "固定演示判决。"}
                                   for claim in state["draft"]["claims"]], "coverage": "complete"}
        elif question == SCENARIOS["refuse"]:
            decision = {"action": "refuse", "missing": "缺少调查观测值或结果。"}
        elif not state["evidence"]:
            decision = {"action": "search", "query": "导数"}
        elif question == SCENARIOS["read"] and not any(chunk["id"] == "demo:2" for chunk in state["evidence"]):
            decision = {"action": "read", "anchor_ids": ["demo:1"]}
        else:
            person = question == SCENARIOS["read"]
            decision = {"action": "draft", "claims": [{"id": "c1", "text": "本合成演示中的蓝桥编码由陈雨发明。" if person else "导数表示函数的局部变化率。",
                "covers": [1], "citations": [{"chunk_id": "demo:2" if person else "demo:0",
                    "quote": "蓝桥编码由陈雨发明" if person else "导数表示函数的局部变化率"}]}]}
        return Completion(json.dumps(decision, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=list(SCENARIOS), default="answer")
    args = parser.parse_args()
    result = asyncio.run(AgentRunner(DemoModel(), DemoTools()).run(demo_request(args.scenario)))
    print(json.dumps({"mode": "scripted_demo", "external_model_calls": 0, "result": result.model_dump()}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
