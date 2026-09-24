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
| 会话 | `POST /api/conversations`；`GET /api/conversations`；`GET /api/conversations/{conversationId}`；`PUT /api/conversations/{conversationId}/documents`；`GET /api/conversations/{conversationId}/messages` |
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

上表共 57 个当前映射端点。路径中 `{id}` 为路径参数，问号后的 `documentId`、`conversationId` 等为查询参数。

## 关键请求

- `POST /api/tutor-agent/chat`：`conversationId` 必填，`message` 必填且不超过 4000；可选 `learningSessionId`。返回回答、意图、学习会话、策略、知识图谱上下文、记忆更新和导航动作。
- `POST /api/conversations`：创建 `mode=rag` 的会话时可传 `documentIds`；省略或空数组表示尚未选择教材。普通会话不接受非 `null` 的 `documentIds` 字段。返回 `conversationId`。
- `GET /api/conversations` 和 `GET /api/conversations/{conversationId}`：返回会话及其 `documentIds` 数组。读取会话前校验用户归属；旧会话尚未绑定时返回空数组。教材已删除时保留原 ID，供页面提示重新选择。
- `PUT /api/conversations/{conversationId}/documents`：请求为 `{"documentIds":[1,2]}`，仅允许更新本人的 RAG 会话，返回更新后的会话。数组必填、最多 20 项，ID 为正整数，去重后保存；传 `[]` 清空教材选择。
- `POST /api/ai/rag/chat`：`conversationId` 和 `question` 必填。省略 `documentIds`（或传 `null`）使用会话已保存的教材；显式传非空列表则校验并保存为新的会话选择。显式空数组或会话没有教材返回业务码 `400`，不会自动检索全部文档。成功返回回答和引用片段；历史消息暂未保存结构化引用。
- `POST /api/questions/generate`：`knowledgePointId` 必填；题型支持 `single_choice`、`true_false`、`short_answer`，数量限制 1–5。
- `POST /api/documents/upload`：表单字段名为 `file`，支持 TXT/Markdown/PDF/DOC/DOCX，默认最大 5 MB。解析完成且分片不超过 100 段时自动创建异步个人图谱提取，需轮询提取端点。超过 100 段仍可上传和教材问答，但 `personalGraphExtractionId` 为 `null`；重处理遵循相同规则。手动提取图谱超过此限制时返回业务码 `400`，需拆分资料。
- `POST /api/personal-graph/extractions/{id}/publish`：只有完成提取且属于当前用户时可发布；发布不是自动发生的。

教材绑定和每次问答均重新检查所有选中文档的归属及处理状态。任何一份已删除、不存在或属于他人，整个请求返回 `404`；尚未处理完成返回 `409`。无效选择不会写入用户消息或替换已有绑定，不会静默缩小或扩大检索范围。校验通过但没有合格片段时，返回证据不足和空引用，不调用模型。

没有记录在上表中的 SSE、Python 服务、任意工具调用或计划中的学习记录接口，不能作为当前 API 使用。
