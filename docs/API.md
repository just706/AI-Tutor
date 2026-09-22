# HTTP API

## 通用约定

接口前缀为 `/api`，成功响应为 `{code:200,message,data}`。认证接口为注册和登录；健康检查公开；其余接口需要 JWT。控制器通常返回 HTTP 200，业务结果由 `code` 表示，常见值为 400、401、403、404、409、500、600。

请求字段和返回字段以 `backend/src/main/java/com/aitutor/dto`、`vo` 及控制器源码为准；本文只保留路由索引和关键行为，避免复制一份会漂移的 Java 类型定义。

## 路由索引

| 模块 | 方法与路径 |
| --- | --- |
| 健康 | `GET /api/health`；`GET /api/health/db` |
| 认证 | `POST /api/auth/register`；`POST /api/auth/login` |
| 用户/档案 | `GET /api/users/me`；`GET /api/profile`；`PUT /api/profile` |
| 会话 | `POST /api/conversations`；`GET /api/conversations`；`GET /api/conversations/{conversationId}/messages` |
| AI 聊天 | `POST /api/ai/chat`；`POST /api/ai/orchestrator/chat`；`POST /api/tutor-agent/chat`；`POST /api/ai/rag/chat` |
| 学习会话 | `GET /api/learning-sessions/active`；`POST /api/learning-sessions/{learningSessionId}/close` |
| 记忆 | `GET /api/learner-memories`；`PATCH /api/learner-memories/{memoryId}/suppress`；`DELETE /api/learner-memories/{memoryId}` |
| 知识点/地图 | `GET /api/knowledge-points/tree`；`GET /api/knowledge-map/graph` |
| 管理知识点 | `POST /api/admin/knowledge-points`；`PUT /api/admin/knowledge-points/{id}`；`DELETE /api/admin/knowledge-points/{id}` |
| 教学 | `POST /api/teaching/start`；`POST /api/teaching/evaluate`；`GET /api/teaching/records` |
| 题目 | `POST /api/questions/generate`；`GET /api/questions`；`GET /api/questions/{questionId}`；`POST /api/questions/{questionId}/answer` |
| AI 路径/练习 | `POST /api/ai/path/generate`；`POST /api/ai/practice/generate` |
| 学习分析/计划 | `GET /api/analysis/overview`；`GET /api/analysis/knowledge-points`；`GET /api/analysis/recent-answers`；`POST /api/study-plans/generate` |
| 文档 | `POST /api/documents/upload`；`GET /api/documents`；`GET /api/documents/{documentId}`；`GET /api/documents/{documentId}/chunks`；`POST /api/documents/{documentId}/reprocess`；`DELETE /api/documents/{documentId}` |
| Agent 建议 | `POST /api/agents/suggestions/generate`；`GET /api/agents/suggestions`；`GET /api/agents/suggestions/{suggestionId}/events`；`POST /api/agents/suggestions/{suggestionId}/confirm`；`POST /api/agents/suggestions/{suggestionId}/complete`；`POST /api/agents/suggestions/{suggestionId}/dismiss` |
| 个人图谱 | `POST /api/personal-graph/extractions`；`GET /api/personal-graph/extractions/{extractionId}`；`GET /api/personal-graph/extractions?documentId=`；`POST /api/personal-graph/extractions/{extractionId}/publish`；`GET /api/personal-graph?documentId=` |
| 评估 | `GET /api/evaluation/overview` |

上表共 55 个当前映射端点。路径中 `{id}` 为路径参数，问号后的 `documentId`、`conversationId` 等为查询参数。

## 关键请求

- `POST /api/tutor-agent/chat`：`conversationId` 必填，`message` 必填且不超过 4000；可选 `learningSessionId`。返回回答、意图、学习会话、策略、知识图谱上下文、记忆更新和导航动作。
- `POST /api/ai/rag/chat`：`conversationId` 和问题必填；可选 `documentIds`，为空时使用当前用户所有已完成文档。返回回答和引用片段。
- `POST /api/questions/generate`：`knowledgePointId` 必填；题型支持 `single_choice`、`true_false`、`short_answer`，数量限制 1–5。
- `POST /api/documents/upload`：表单字段名为 `file`，支持 TXT/Markdown/PDF/DOC/DOCX，默认最大 5 MB。上传完成后可能创建异步个人图谱提取，需轮询提取端点。
- `POST /api/personal-graph/extractions/{id}/publish`：只有完成提取且属于当前用户时可发布；发布不是自动发生的。

没有记录在上表中的 SSE、Python 服务、任意工具调用或计划中的学习记录接口，不能作为当前 API 使用。
