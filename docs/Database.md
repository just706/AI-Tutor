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
```

`repair-stage6-encoding.sql` 是针对已有环境的字符集修复脚本，不属于新库必跑步骤。脚本中的 `INSERT IGNORE`、更新和条件加列行为仍应在目标库上先检查。

Windows 示例（在项目根目录打开 MySQL 客户端后执行）：

```sql
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage1-user.sql;
SOURCE D:/AI-Tutor/backend/src/main/resources/db/stage2-student-profile.sql;
-- 依次执行上面的其余文件
```

## 表清单

| 表 | 用途 | 所有权/关键约束 |
| --- | --- | --- |
| `user` | 账号与角色 | `username` 唯一 |
| `student_profile` | 学习方向、目标、水平、偏好 | 每用户一份 |
| `conversation` | 会话 | 归属 `user_id` |
| `chat_history` | 用户/助手消息 | 通过会话归属用户 |
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

掌握度当前由学习记录保存，服务层按历史最高值更新；没有唯一的活动会话约束，因此并发创建仍需应用层关注。SQL 没有用外键表达级联删除，文档、分片和图谱清理由服务层完成。

## 后续迁移建议

在脚本继续增加前，应引入明确版本、校验和、升级前检查和回滚说明。向量检索若采用 PostgreSQL/pgvector，应把它当作独立存储并用 `documentId`、`chunkId` 关联，不要在没有评测集的情况下直接替换 MySQL 业务库。
