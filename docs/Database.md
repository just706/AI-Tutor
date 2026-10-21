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
stage21-tutor-flow.sql
stage22-learning-evidence.sql
stage23-review-tasks.sql
stage24-teaching-binding.sql
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

## 已有库升级循序学习

启动新后端前执行以下脚本，数据库名须与运行配置一致：

```sql
USE ai_tutor;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage21-tutor-flow.sql;
```

脚本只在字段不存在时新增可空 JSON 列 `learning_session.flow_state`，可重复执行；旧会话保持 NULL，不修改消息、成绩或掌握度。升级前保存表结构和记录数量，应用回滚时保留新增字段即可。

该字段保存流程版本、阶段、主题、用户补充的参考资料、备课内容、当前题目位置、作答证据和反馈。正确选项仅供服务端核对，学习会话接口只返回当前题干、选项、阶段、版本和可执行动作，不返回答案键或后续题库。教师请求与结束会话使用同一所属会话行锁；版本过期返回 409。每次成功推进在同一事务内保存进度、教学步骤及聊天记录，失败回滚到上一步。没有增加新表，也不写 `learning_record` 掌握度。

教材循序学习沿用 `flow_state`，增加可空 `materials` 对象，保存整轮所选 `documentIds` 与有序 `sources`（文档 ID、文件名、片段索引、原文）；`source` 保存实际备课上下文。旧手动流程缺少该对象时按原流程读取。每条对应助手消息复用 `chat_history.rag_sources` 保存同一引用；公开学习会话仅增加 `textbookBased`，引用从回答或历史读取并再次校验教材权限。无需新建表或新增迁移。首次备课及后续推进在已有教师事务内对选中教材加共享行锁，避免处理或删除与本次保存交错；会话教材更新也使用会话行锁，并拒绝活动循序学习期间的修改。

选项补讲也保存在同一 JSON 中：每个 `lesson` 题目可带 `wrongOptionFeedback` 数组。schema 4 模型反馈提交 `option,distinctionId,explanation,evidenceIds`，服务端将标签 ID 解析为保存用的 `focus`，并将证据编号还原为 `{id,text}` 列表；原 `focus,sourceQuote` 字段保留为可空以兼容旧快照。模型响应为统一五题列表，转换后的持久化结构仍是 `diagnosis/checks/practices`。历史题库缺少反馈时按旧通用补讲继续；已有单引文时按旧字段显示，不回填、不重新调用模型。渲染时使用已保存题干和最近作答恢复对应反馈；无需修改表结构，已执行 stage21 的库直接兼容。

新学习另在同一 JSON 中保存可空 `selection`：新备课的 `policy` 为 `distinction_then_evidence_v2`，旧快照仍可使用 `evidence_overlap_v1`；`history` 的每项包含 `stage,question,reason,basedOnQuestion,selectedOption,sharedEvidenceIds`。新策略原因取 `ORIGINAL_ORDER/DISTINCTION_MATCH/NO_DISTINCTION_MATCH`，旧策略原因取 `ORIGINAL_ORDER/SHARED_EVIDENCE/NO_SHARED_EVIDENCE`；无历史错误时依据问题和选项为空。它是服务端私有记录，公开接口只返回当前题的可空选题说明。`checkIndex/practiceIndex` 在新策略下是已选题的位置，不再保证递增，已作答题依据 `evidence` 排除。旧 JSON 缺少或为空时继续原固定顺序，不回填、不重新备课；无需迁移。旧二进制不识别新增字段，回退应用时须先做快照兼容处理，不能直接用旧版读取新策略进度。

## 已有库升级学习证据

使用新版本前，在配置的数据库执行 `stage22-learning-evidence.sql`。该脚本只新增 `learning_evidence` 表，可重复执行，不修改或回填原学习、答题、会话记录。

```sql
USE ai_tutor;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage22-learning-evidence.sql;
```

表中 `snapshot` 保存评分时的题目／教学上下文 JSON；`response_json` 保存去重重放的原响应。`score` 可空，`assessment_type` 区分答案键客观比对和模型评价，`prior_evidence_id` 指向前次有效评分；状态前后值、原因、规则版本及时间支持追溯。`user_id,source_key` 唯一，哈希包含入口、题目或教学会话与知识点、规范化答案；不向接口暴露哈希和内部响应。所有权只由当前登录用户限定，不接受客户端指定用户。

计分写入在同一事务中锁定用户行，再以当前读查询去重记录、状态与前次证据，避免可重复读旧快照导致重复计分。该首批方案会让同一用户的计分入口在同步模型调用期间串行等待；进程中断或提交失败后仍可能再次调用上游，不保证上游恰好一次。

## 已有库升级复习任务

已有库在 stage22 后执行 `stage23-review-tasks.sql`，新增 `review_task` 表；可重复执行，不回填任务或修改旧成绩。`source_evidence_id` 关联生成任务的证据，`result_evidence_id` 在有效评分完成时关联本次证据；快照、到期时间与完成时间均持久保存。同一用户同一来源证据只生成一次；服务层在用户锁下限制每知识点仅一个待完成任务。未评分尝试存于 `learning_evidence`，任务保持 pending。

```sql
USE ai_tutor;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage23-review-tasks.sql;
```

## 已有库升级教学知识点绑定

在 stage23 后执行 `stage24-teaching-binding.sql`，仅为 `conversation` 新增可空的 `teaching_knowledge_point_id BIGINT`，可重复执行。新教学会话保存服务端加载的知识点ID，评价必须匹配该绑定。旧会话保持NULL，评价返回409并要求重新开始教学；不从可修改或截断的标题推断绑定，不改写旧消息、证据或成绩。

```sql
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage24-teaching-binding.sql;
```

## 表清单

| 表 | 用途 | 所有权/关键约束 |
| --- | --- | --- |
| `user` | 账号与角色 | `username` 唯一 |
| `student_profile` | 学习方向、目标、水平、偏好 | 每用户一份 |
| `conversation` | 会话、教材选择与教学知识点绑定 | 归属 `user_id`；`document_ids` 保存最多 20 个去重的教材 ID；`teaching_knowledge_point_id` 仅在教学开始时设置 |
| `chat_history` | 消息、RAG 引用与请求恢复状态 | 通过会话归属用户；引用及上下文为可空 JSON；同一请求的角色唯一 |
| `ai_call_log` | 模型调用、错误和时延 | 关联用户/会话可为空 |
| `knowledge_point` | 公共知识点树 | `subject,parent_id,name` 唯一组合 |
| `learning_record` | 用户知识点状态 | `user_id,knowledge_point_id` 组合唯一 |
| `question` | 题干、选项、答案和解析 | 公共题目，不带用户所有权 |
| `answer_record` | 用户答题结果 | 通过用户和题目归属 |
| `learning_evidence` | 评分快照、状态变化及原结果恢复 | 按 `user_id` 隔离，`user_id,source_key` 唯一，不回填旧记录 |
| `review_task` | 复习题快照、到期与完成时间、来源和结果依据 | 按用户隔离，`user_id,source_evidence_id` 唯一 |
| `document` | 用户上传文档 | `user_id` 是所有权边界 |
| `document_chunk` | 文档分片 | 通过 `document_id` 继承所有权 |
| `agent_suggestion` | 规则生成的建议 | 归属用户，状态可确认/完成/驳回 |
| `agent_event_log` | 建议事件 | 通过建议归属用户 |
| `learning_session` | 教师学习会话与循序学习进度 | 归属用户和会话；`flow_state` 为服务端私有快照 |
| `learning_session_step` | 会话步骤与快照 | 通过 session/user 归属 |
| `knowledge_map_dependency` | 公共知识点前置关系 | 前置点和依赖点组合唯一 |
| `learner_memory` | 偏好、困难模式、误解 | 归属用户，状态可过期/停用 |
| `personal_graph_extraction` | 异步图谱任务 | 通过文档继承用户，记录状态和进度 |
| `personal_knowledge_node` | 已发布个人节点 | `user_id,document_id,name_key` 唯一 |
| `personal_knowledge_edge` | 已发布个人关系 | 用户、源节点、目标节点、类型组合唯一 |

## 语义边界

没有 `study_plan`、`learner_model` 或向量表；学习计划是实时规则结果，RAG 分片当前保存在 `document_chunk` 并使用关键词评分。`question.options`、会话策略来源和动作快照以文本形式保存 JSON，个人图谱候选和证据使用 JSON 字段。

删除教材后不删除会话中的原教材 ID；问答前再次检查权限及可用性，页面提示重新选择。历史引用快照仍随消息保存在数据库，但接口不返回失效教材的文件名和原文。教材重处理或会话更换教材不会改写旧快照；仍有权访问且已完成处理时可读取回答当时的原文。

掌握度当前由学习记录保存，持久化练习、教学评价及复习按 `recent-evidence-v1` 更新，规则见 [AI-Design](AI-Design.md#学习会话与掌握度)。没有唯一的活动会话约束，因此并发创建仍需应用层关注。SQL 没有用外键表达级联删除，文档、分片和图谱清理由服务层完成。

## 后续迁移建议

在脚本继续增加前，应引入明确版本、校验和、升级前检查和回滚说明。向量检索若采用 PostgreSQL/pgvector，应把它当作独立存储并用 `documentId`、`chunkId` 关联，不要在没有评测集的情况下直接替换 MySQL 业务库。
