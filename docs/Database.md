# 数据库

## 现状

当前使用 MySQL，数据库名默认 `ai_tutor`，字符集脚本通常使用 `utf8mb4`。项目没有 Flyway、Liquibase 或迁移版本表，也没有 SQL 外键；关联和用户所有权由服务层查询保证。已有库执行脚本前要检查现状，不能把初始化脚本当作可重复升级工具。

## 执行顺序

新库按以下顺序执行，数字是业务依赖顺序，不要按文件名字典序执行：

```text
stage1-user.sql
stage2-student-profile.sql
stage3-conversation-chat.sql
stage4-ai-chat.sql
stage6-teaching.sql
stage7-question-answer.sql
stage8-rag.sql
stage9-analysis.sql       # 当前为兼容/说明脚本，通常无新增表
stage10-agent.sql
stage12-learning-session.sql
stage13-knowledge-map.sql
stage14-learner-memory.sql
stage15-evaluation-governance.sql
stage16-personal-graph.sql
stage17-async-personal-graph.sql
stage18-conversation-documents.sql
stage19-chat-rag-sources.sql
stage20-rag-retry.sql
```

`repair-stage6-encoding.sql` 是针对已有环境的字符集修复脚本，不属于新库必跑步骤。脚本中的 `INSERT IGNORE`、更新和条件加列行为仍应在目标库上先检查。

Windows 示例（在项目根目录打开 MySQL 客户端后执行）：

```sql
CREATE DATABASE IF NOT EXISTS ai_tutor CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_tutor;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage1-user.sql;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage2-student-profile.sql;
-- 依次执行上面的其余文件
```

如果在 `.env` 中使用其他数据库名，相应调整建库和 `USE` 的名称；执行 `SOURCE` 前必须先选中目标数据库。

## 已有库升级教材绑定

使用新后端前，在已配置的目标数据库执行 `stage18-conversation-documents.sql`。该脚本检查列是否存在，只新增可空 JSON 列 `conversation.document_ids`，可重复执行，不回填旧会话，也不改动消息或教材。示例（已进入 MySQL 客户端）：

```sql
USE ai_tutor;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage18-conversation-documents.sql;
```

数据库名与 `.env` 保持一致。升级后旧教材会话需要重新选择一次教材；`NULL` 或 `[]` 均表示尚未选择，不代表使用全部资料。

## 已有库升级引用保存

在启动本版本后端前，执行 `stage19-chat-rag-sources.sql`。它只新增可空 JSON 列 `chat_history.rag_sources`，可重复执行，旧消息保持 `NULL`，不推断或回填旧引用。

```sql
USE ai_tutor;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage19-chat-rag-sources.sql;
```

JSON 数组按回答时的证据顺序保存文档 ID、当时文件名、从 0 开始的片段索引及完整片段原文（最多 8 段）。助手回答与引用在同一事务内写入；历史读取时重新检查文档归属及可用性。普通消息不保存引用快照，无依据拒答的引用为空数组。

## 已有库升级失败重试

启动新后端前执行以下脚本，可重复执行，仅增加可空字段和唯一索引，不更新既有消息内容：

```sql
USE ai_tutor;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage20-rag-retry.sql;
```

新增字段均位于 `chat_history`，没有新增表：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `rag_request_id` | VARCHAR(36)，ascii_bin | 客户端请求标识，问题与回答共享 |
| `rag_status` | VARCHAR(16) | 用户消息的 processing / failed / completed 状态 |
| `rag_attempt` | INT | 用户请求当前尝试次数，从 1 开始 |
| `rag_context` | JSON | 当时教材 ID、明确后的问题、可选澄清；不直接返回客户端 |
| `rag_retry_after` | DATETIME(6) | 处理中请求允许恢复的时间边界 |

唯一索引 `uk_chat_rag_request_role(user_id, conversation_id, rag_request_id, role)` 保证同一请求至多一条用户消息和一条助手消息。旧消息的标识保持 NULL，不受该去重约束影响，也不会被推断为可重试。历史接口将已过期的 processing 展示为 interrupted，不需要读取时写库。处理中有效期为 `max(180 秒, (2 × 模型 timeoutMs + 30000) / 1000 秒向下取整)`。

升级前保存表结构并核对消息数量。回滚应用时可以保留新增字段；不要为了回滚删除已产生的请求状态或引用数据。问题先单独提交，回答、引用、完成状态和成功日志在另一个短事务中写入。模型失败后的状态与失败日志单独提交；数据库不可用时状态可能暂留 processing，恢复后可检查或等待过期再重试。

## 表清单

| 表 | 用途 | 所有权/关键约束 |
| --- | --- | --- |
| `user` | 账号与角色 | `username` 唯一 |
| `student_profile` | 学习方向、目标、水平、偏好 | 每用户一份 |
| `conversation` | 会话与教材选择 | 归属 `user_id`；`document_ids` 保存最多 20 个去重的教材 ID |
| `chat_history` | 消息、RAG 引用与请求恢复状态 | 通过会话归属用户；引用及上下文为可空 JSON；同一请求的角色唯一 |
| `ai_call_log` | 模型调用、错误和时延 | 关联用户/会话可为空 |
| `knowledge_point` | 公共知识点树 | `subject,parent_id,name` 唯一组合 |
| `learning_record` | 用户知识点状态 | `user_id,knowledge_point_id` 组合唯一 |
| `question` | 题干、选项、答案和解析 | 公共题目，不带用户所有权 |
| `answer_record` | 用户答题结果 | 通过用户和题目归属 |
| `document` | 用户上传文档 | `user_id` 是所有权边界 |
| `document_chunk` | 文档分片 | 通过 `document_id` 继承所有权 |
| `agent_suggestion` | 规则生成的建议 | 归属用户，状态可确认/完成/驳回 |
| `agent_event_log` | 建议事件 | 通过建议归属用户 |
| `learning_session` | 教师学习会话 | 归属用户和会话 |
| `learning_session_step` | 会话步骤与快照 | 通过 session/user 归属 |
| `knowledge_map_dependency` | 公共知识点前置关系 | 前置点和依赖点组合唯一 |
| `learner_memory` | 偏好、困难模式、误解 | 归属用户，状态可过期/停用 |
| `personal_graph_extraction` | 异步图谱任务 | 通过文档继承用户，记录状态和进度 |
| `personal_knowledge_node` | 已发布个人节点 | `user_id,document_id,name_key` 唯一 |
| `personal_knowledge_edge` | 已发布个人关系 | 用户、源节点、目标节点、类型组合唯一 |

## 语义边界

没有 `study_plan`、`learner_model` 或向量表；学习计划是实时规则结果，RAG 分片当前保存在 `document_chunk` 并使用关键词评分。`question.options`、会话策略来源和动作快照以文本形式保存 JSON，个人图谱候选和证据使用 JSON 字段。

删除教材后不删除会话中的原教材 ID；问答前再次检查权限及可用性，页面提示重新选择。历史引用快照仍随消息保存在数据库，但接口不返回失效教材的文件名和原文。教材重处理或会话更换教材不会改写旧快照；仍有权访问且已完成处理时可读取回答当时的原文。

掌握度当前由学习记录保存，服务层按历史最高值更新；没有唯一的活动会话约束，因此并发创建仍需应用层关注。SQL 没有用外键表达级联删除，文档、分片和图谱清理由服务层完成。

## 后续迁移建议

在脚本继续增加前，应引入明确版本、校验和、升级前检查和回滚说明。向量检索若采用 PostgreSQL/pgvector，应把它当作独立存储并用 `documentId`、`chunkId` 关联，不要在没有评测集的情况下直接替换 MySQL 业务库。
