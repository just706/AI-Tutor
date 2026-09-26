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
- `POST /api/ai/rag/chat`：`conversationId` 和 `question` 必填。新请求省略 `documentIds`（或传 `null`）使用会话已保存的教材；显式传非空列表则校验并保存为新的会话选择。显式空数组或会话没有教材返回业务码 `400`，不会自动检索全部文档。成功返回回答、引用片段、`requestId` 和 `attempt`，并将引用快照与对应助手消息一起保存；失败重试协议见下文。
- `GET /api/conversations/{conversationId}/messages`：每条消息增加 `sources` 数组，顺序与该次模型请求的“片段1、片段2……”一致。每项含 `documentId`、`fileName`、从 0 开始的 `chunkIndex`、完整片段原文 `snippet` 和 `available`。界面将原始索引加 1 显示为教材位置；该位置与本次回答的引用序号不同。
- `POST /api/questions/generate`：`knowledgePointId` 必填；题型支持 `single_choice`、`true_false`、`short_answer`，数量限制 1–5。
- `POST /api/documents/upload`：表单字段名为 `file`，支持 TXT/Markdown/PDF/DOC/DOCX，默认最大 5 MB。解析完成且分片不超过 100 段时自动创建异步个人图谱提取，需轮询提取端点。超过 100 段仍可上传和教材问答，但 `personalGraphExtractionId` 为 `null`；重处理遵循相同规则。手动提取图谱超过此限制时返回业务码 `400`，需拆分资料。
- `POST /api/personal-graph/extractions/{id}/publish`：只有完成提取且属于当前用户时可发布；发布不是自动发生的。

教材绑定和每次新生成回答均重新检查所有选中文档的归属及处理状态。任何一份已删除、不存在或属于他人，整个请求返回 `404`；尚未处理完成返回 `409`。无效选择不会写入用户消息或替换已有绑定，不会静默缩小或扩大检索范围。校验通过但没有合格片段时，返回证据不足和空引用，不调用模型。

教材问答遇到“它”“那两种”“前者”“后者”等指代时，只从本人当前会话最近 10 条消息中的用户问题重建对象；助手回答不参与。能够明确时，使用补全对象的问题检索当前教材并发送给模型，`answer` 开头显示 `本次按“明确后的问题”理解你的追问。`。用户消息仍保存原问题。对象缺失、单复数不匹配或规则无法消除歧义时，返回业务码 `200`、澄清文字及 `sources:[]`，保存该轮用户问题与澄清，不调用模型。指代解析本身不调用额外模型；支持范围见 [AI-Design](AI-Design.md#rag-当前基线)。

历史引用读取再次检查教材归属及处理状态。教材已删除、不属于当前用户或未完成处理时，保留引用数组的位置和文档 ID/索引，返回 `available=false`，`fileName`、`snippet` 为 `null`。切换教材不会改变旧回答的引用；仍有权访问的教材重处理后，旧回答返回回答时保存的原文。用户消息、普通回答、拒答及升级前未保存引用的消息返回 `sources:[]`，不从答案文本推断引用。

## 教材问答重试协议

新客户端在每次新问题中发送小写 UUID 格式的 `requestId` 和 `attempt:1`；question 最长 5000 字符。attempt 为 1–1000000 的整数，省略或 null 按 1 处理。旧客户端省略 requestId 时由服务端生成，返回值和历史中均可获取；不保留该标识的旧客户端重复发送仍被视为新问题。

- 相同用户、会话和标识对应同一个问题。question 去除首尾空白后必须相同，显式 documentIds 必须与首次请求列表相同，否则返回 409。重试通常省略 documentIds。
- processing 状态仍有效时返回 409，不启动第二次模型调用。读取历史可查看处理状态；客户端的网络超时不等于服务端失败。
- failed 状态重复同一 attempt 返回 600，不重新调用模型；用户明确重试时发送当前 attempt + 1。过期 processing 也须使用下一次 attempt 才能恢复；跳号或旧尝试返回 409。
- completed 状态再次提交返回已保存回答，不重新调用模型，引用仍重新检查权限和可用性。请求字段冲突仍先返回 409。
- 重试沿用首次保存的教材 ID 和明确后的追问对象，不修改当前会话教材绑定；重新校验教材并重新检索当前片段。教材已删除或不可用时拒绝重试，须重新选择教材并发起新问题。教材重处理后内容可能变化，重试不承诺复用原片段。

历史用户消息带 `ragRequestId`、`ragAttempt`、`ragStatus`；状态为 processing、failed、completed 或由过期 processing 派生的 interrupted。助手消息带相同 ragRequestId，状态和次数为 null；旧消息这些字段均为 null。晚完成的回答排在原问题之后。请求上下文和过期时间不返回客户端。前端自身可能临时显示 uncertain，表示网络结果未确认，不是数据库状态。

此协议仅用于教材问答；不自动回退普通模型、不自动增加尝试次数。持久化与并发边界见 [Architecture](Architecture.md#一致性与故障边界)，迁移见 [Database](Database.md#已有库升级失败重试)。

引用数组是本次检索提供给模型的证据列表，不代表已经逐句核验模型回答。没有记录在上表中的 SSE、Python 服务、任意工具调用或计划中的学习记录接口，不能作为当前 API 使用。

教材问答没有 Java 学科参数或概念白名单。对象由受支持的问句结构确定，例如先问“什么是导数”，再问“它有什么用途”；同一接口也支持物理、经济学或用户教材中的自定义名称。对象明确后仍须在当前选定教材中检索到所问内容；切换教材不等于自动改写历史对象，应明确提出新主题。复杂句式与语义改写仍可能需要澄清或出现词法漏检。
