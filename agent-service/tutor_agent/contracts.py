from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]
Identifier = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_.:-]{1,100}$")]
QuestionId = Annotated[int, Field(ge=1, le=4)]


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class RunRequest(Contract):
    version: Literal["1"] = "1"
    request_id: Identifier
    attempt: Annotated[int, Field(ge=1, le=100)] = 1
    user_id: Annotated[int, Field(gt=0)]
    conversation_id: Annotated[int, Field(gt=0)]
    document_ids: Annotated[list[Annotated[int, Field(gt=0)]], Field(min_length=1, max_length=20)]
    scope_version: Identifier
    scope_token: Annotated[str, StringConstraints(min_length=1, max_length=2048)] = Field(repr=False)
    question: Text
    recent_user_questions: Annotated[list[Text], Field(max_length=10)] = Field(default_factory=list)

    @model_validator(mode="after")
    def unique_documents(self):
        if len(set(self.document_ids)) != len(self.document_ids):
            raise ValueError("Duplicate document IDs")
        return self


class Chunk(Contract):
    id: Identifier
    document_id: Annotated[int, Field(gt=0)]
    chunk_index: Annotated[int, Field(ge=0)]
    revision: Identifier
    text: Annotated[str, StringConstraints(min_length=1, max_length=4000)]


class ToolResult(Contract):
    request_id: Identifier
    attempt: Annotated[int, Field(ge=1, le=100)]
    scope_version: Identifier
    chunks: Annotated[list[Chunk], Field(max_length=8)]


class Subquestion(Contract):
    id: QuestionId
    question: Text


class Plan(Contract):
    action: Literal["search", "clarify"]
    questions: Annotated[list[Subquestion], Field(max_length=4)]
    query: Text | None = None
    clarification: Text | None = None

    @model_validator(mode="after")
    def valid_plan(self):
        if self.action == "search":
            if not self.questions or self.query is None or self.clarification is not None:
                raise ValueError("Search plan requires questions and a query")
            if len({item.id for item in self.questions}) != len(self.questions):
                raise ValueError("Duplicate subquestion IDs")
        elif self.clarification is None or self.query is not None or self.questions:
            raise ValueError("Clarification requires only clarification text")
        return self


class Citation(Contract):
    chunk_id: Identifier
    quote: Annotated[str, StringConstraints(min_length=1, max_length=2000)]


class Claim(Contract):
    id: Identifier
    text: Text
    covers: Annotated[list[QuestionId], Field(min_length=1, max_length=4)]
    citations: Annotated[list[Citation], Field(min_length=1, max_length=6)]


class Search(Contract):
    action: Literal["search"]
    query: Text


class Read(Contract):
    action: Literal["read"]
    anchor_ids: Annotated[list[Identifier], Field(min_length=1, max_length=3)]


class Draft(Contract):
    action: Literal["draft"]
    claims: Annotated[list[Claim], Field(min_length=1, max_length=8)]


class Clarify(Contract):
    action: Literal["clarify"]
    question: Text


class Refuse(Contract):
    action: Literal["refuse"]
    missing: Text


Decision = Annotated[Search | Read | Draft | Clarify | Refuse, Field(discriminator="action")]


class ClaimReview(Contract):
    claim_id: Identifier
    verdict: Literal["supported", "unsupported", "unknown"]
    reason: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=400)]


class Review(Contract):
    claims: Annotated[list[ClaimReview], Field(min_length=1, max_length=8)]
    coverage: Literal["complete", "incomplete", "unknown"]


class TraceEvent(Contract):
    step: int
    operation: str
    outcome: str
    elapsed_ms: float


class RunResult(Contract):
    version: Literal["1"] = "1"
    request_id: Identifier
    attempt: int
    status: Literal["answered", "clarification", "insufficient", "budget_exhausted", "timeout", "upstream_error", "invalid_output", "invalid_evidence"]
    message: str
    stop_reason: Literal["repeated_query", "search_limit", "read_limit", "review_reserved"] | None = None
    answer: str | None = None
    claims: list[Claim] = Field(default_factory=list)
    sources: list[Chunk] = Field(default_factory=list)
    model_calls: int = 0
    searches: int = 0
    reads: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    usage_complete: bool = True
    trace: list[TraceEvent] = Field(default_factory=list)
    semantic_acceptance: Literal["not_evaluated"] = "not_evaluated"
