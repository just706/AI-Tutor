# AI-Tutor Agent v1 Upgrade

## 1. Positioning

AI-Tutor v1 is a Chat-first personalized learning Agent.

The product is not a Java interview drill tool and not a general education platform. The v1 goal is to build an extensible learning Agent core, then use Java backend learning as the first subject pack to prove the loop.

The core loop is:

```text
user message
-> Tutor Agent
-> intent detection
-> ChatSession context
-> LearningSession state
-> learning action suggestion
-> response
-> step record
```

## 2. Phase Plan

### Phase 0: Documentation and Stability

Deliverables:

- Add this upgrade document as the single v1 master plan.
- Keep detailed frontend, backend, API, and database docs factual instead of creating separate planning documents for every change.
- Fix visible Chinese mojibake encountered during implementation.
- Verify frontend build and backend compile/test where the local environment allows it.
- Preserve existing uncommitted changes.

### Phase 1: ChatSession and LearningSession Minimum Loop

Deliverables:

- Keep `conversation` and `chat_history` as ChatSession storage.
- Add `learning_session` for one goal-oriented learning task.
- Add `learning_session_step` for each Agent decision and response in that task.
- Add `POST /api/tutor-agent/chat`.
- Let Chat UI show the active LearningSession status, goal, strategy placeholder, and next action.

Phase 1 intentionally does not implement full Teaching Strategy, long-term Memory, deep Knowledge Map/RAG decisioning, full Evaluation, multi-Agent frameworks, MQ, Redis, or microservices.

### Phase 2: Teaching Strategy Layer

Teach the Agent how to choose a teaching method, not only identify what the user does not know.

Reserved strategy names:

- `concept_first`
- `example_first`
- `source_code_first`
- `prerequisite_first`
- `practice_first`
- `debug_misconception`
- `summary_review`

Each strategy decision must include `strategySource` evidence.

### Phase 3: Knowledge Map and RAG Decision Participation

The earlier standalone Knowledge Graph implementation is intentionally removed from Phase 1 and should be redesigned before reintroduction.

The future Knowledge Map should become the learning path map. It must influence prerequisite checks, path recommendation, and next-step decisions instead of being only a visualization page.

RAG is positioned as Learning Knowledge Retrieval. It supports explanation, examples, correction, summaries, and citations. It is not the main product surface in v1.

### Phase 4: Memory Lifecycle

Memory remains lightweight in v1 and is limited to:

- `preference`
- `difficulty_pattern`
- `misconception`

Memory must support creation, update, confidence changes, expiration, suppression, and deletion.

### Phase 5: Evaluation and Engineering Governance

Add Agent quality, learning effect, and user experience evaluation.

Key metrics:

- Agent quality: strategy selection accuracy, tool selection accuracy, decision validity, recovery success rate.
- Learning effect: knowledge gain, retention, transfer.
- UX: user acceptance, helpful feedback, session completion.

## 3. Architecture Decisions

### ChatSession

ChatSession is the conversation context. In the current codebase it maps to:

- `conversation`
- `chat_history`

It stores dialogue continuity, not the learning task lifecycle.

### LearningSession

LearningSession is one learning task with a goal, status, topic, next action, and step history.

Status values:

```text
CREATED
DIAGNOSING
PLANNING
TEACHING
PRACTICE
REFLECTION
COMPLETED
```

Phase 1 may also use `CLOSED` for user-closed sessions if a close endpoint is added later.

### LearnerModelService

LearnerModelService is an aggregation service, not a database table.

It will build a learner snapshot from profile, chat history, learning records, practice results, the future Knowledge Map, and later Memory. Phase 1 only reserves the interface shape through response fields and session records.

### Tutor Agent

In Phase 1, Tutor Agent is a thin orchestration layer:

- Reuses existing AI chat and orchestrator behavior.
- Creates or reuses an active LearningSession.
- Records the Agent step.
- Returns structured output for future phases.

It must not become a large rules engine in Phase 1.

## 4. Public Interface

### POST /api/tutor-agent/chat

Request:

```json
{
  "conversationId": 1,
  "message": "我还是不理解 HashMap 为什么线程不安全",
  "learningSessionId": 10
}
```

`learningSessionId` is optional. If omitted, the backend should reuse the active session for the conversation when available.

Response:

```json
{
  "answer": "针对当前上下文生成的教学回复",
  "intent": "concept_difficulty",
  "learningSession": {
    "id": 10,
    "conversationId": 1,
    "goal": "学习 HashMap",
    "topic": "HashMap",
    "intent": "concept_difficulty",
    "status": "REFLECTION",
    "currentStepType": "reflection",
    "nextAction": "换一种解释并降低难度",
    "updateTime": "2026-08-12 10:00:00"
  },
  "teachingStrategy": "debug_misconception",
  "strategySource": [
    "Phase 1 uses intent-based fallback strategy",
    "User message indicates concept difficulty"
  ],
  "toolTraces": [
    "orchestrator_chat",
    "learning_session_step_recorded"
  ],
  "sources": [],
  "memoryUpdates": [],
  "actions": []
}
```

### Later LearningSession Interfaces

Reserved for later phases:

```text
GET  /api/learning-sessions/{id}
POST /api/learning-sessions/{id}/confirm-action
POST /api/learning-sessions/{id}/close
GET  /api/learning-sessions/{id}/report
```

### Later Memory Interfaces

Reserved for Phase 4:

```text
GET    /api/learner-memories
PATCH  /api/learner-memories/{id}/suppress
DELETE /api/learner-memories/{id}
```

## 5. Phase 1 Acceptance

The Phase 1 loop is accepted when:

- A Chat message can create or reuse an active LearningSession.
- Feedback-only messages such as `我还是不懂` reuse the active LearningSession topic and must not overwrite it with weak pronouns.
- LearningSession and LearningSessionStep are persisted.
- `/api/tutor-agent/chat` returns structured Agent output, not only text.
- Chat UI displays active session goal, status, strategy placeholder, and next action.
- Existing Chat, knowledge library, question, and learning record work are not broken.
- Frontend build passes.
- Backend compile/test is run or the blocking local environment issue is documented.
- Windows local backend startup uses `backend/run.cmd`, with Maven dependencies cached under `backend/.m2/repository`.
