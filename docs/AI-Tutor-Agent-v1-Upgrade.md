# AI-Tutor Agent v1 升级方案

## 1. 项目定位

AI-Tutor v1 的定位是：

```text
以 Chat 为入口的个性化学习 Agent
```

它不是 Java 面试刷题工具，也不是通用教育平台。v1 的目标是先实现一个可扩展的学习 Agent Core，再用 Java 后端学习作为第一个样板场景，证明学习闭环可以跑通。

核心闭环：

```text
用户输入
-> Tutor Agent
-> 意图识别
-> ChatSession 对话上下文
-> LearningSession 学习状态
-> 学习行动建议
-> Agent 回复
-> Step 记录
```

v1 的核心原则：

```text
少做组件，多做闭环。
```

现阶段不追求做一个“AI 技术展览馆”，而是优先证明：

```text
系统能理解用户当前学习任务，
能创建或复用学习会话，
能记录学习过程，
能根据反馈维持上下文并调整下一步动作。
```

## 2. 当前阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| Phase 0 | 已完成 | 文档落地、启动方式稳定、构建方式验证、知识图谱当前实现移除并延后重规划 |
| Phase 1 | 已完成 | ChatSession 与 LearningSession 最小闭环已实现并提交 |
| Phase 2 | 已完成 | Teaching Strategy Layer MVP 已实现并接入 Tutor Agent |
| Phase 3 | 已完成 | Knowledge Map MVP 已参与前置知识判断与教学策略解释；RAG 不在本阶段改造范围内 |
| Phase 4 | 未开始 | Memory 生命周期 |
| Phase 5 | 未开始 | Evaluation 与工程治理 |

Phase 1 提交记录：

```text
1fc526f feat: add chat-first learning session loop
```

## 3. Phase Plan

### Phase 0：文档落地与项目稳定

目标：

```text
先把升级方向写清楚，同时处理影响后续开发的本地运行和文档稳定问题。
```

交付内容：

- 新增本文件，作为 v1 总升级方案文档。
- 不为前端、后端、接口、数据库分别创建大量独立计划文档。
- 前端、后端、API、数据库文档只在真实实现发生变化后更新。
- 检查并稳定前后端构建方式。
- 明确 Windows 本地后端启动方式使用 `backend/run.cmd`。
- Maven Wrapper 默认使用项目内依赖缓存 `backend/.m2/repository`。
- 前端启动方式写入联调文档。
- 知识图谱当前实现从 Phase 1 中移除，后续重新规划为 Knowledge Map。

当前状态：

```text
已完成。
```

验证结果：

- 后端编译已通过。
- 前端构建已通过。
- 后端本地启动方式已文档化。
- 前端启动方式已文档化。
- Phase 1 提交前工作区已清理为稳定快照。

相关文档：

- `docs/Deployment.md`
- `docs/MVP-Test.md`
- `docs/API.md`
- `docs/Architecture.md`
- `docs/Database.md`

说明：

数据库或终端里偶发的中文显示乱码，主要与 Windows PowerShell / MySQL 客户端编码有关，不等同于项目源码或前端页面乱码。后续如果发现具体页面或文件乱码，再按具体文件修复。

### Phase 1：ChatSession 与 LearningSession 最小闭环

目标：

```text
明确 Chat 和 Learning Session 的边界，
让 Chat 从普通 AI 问答入口升级为学习 Agent 的交互入口。
```

交付内容：

- 保留 `conversation` 和 `chat_history` 作为 ChatSession 存储。
- 新增 `learning_session` 表，用于表示一次有目标、有状态的学习任务。
- 新增 `learning_session_step` 表，用于记录每一步 Agent 决策和回复。
- 新增 `POST /api/tutor-agent/chat`。
- 新增 active LearningSession 查询接口。
- Chat 前端展示当前 active LearningSession 的目标、状态、策略占位和下一步行动。
- 修复反馈型消息覆盖 topic 的问题，例如 `我还是不懂` 不再把 topic 覆盖成 `我`。

Phase 1 明确不做：

- 不实现完整 Teaching Strategy Layer。
- 不实现长期 Memory 生命周期。
- 不深化 Knowledge Map / RAG 决策能力。
- 不实现完整 Evaluation 系统。
- 不引入 LangGraph / CrewAI / 多 Agent 框架。
- 不做 Redis、MQ、微服务拆分。

当前状态：

```text
已完成，并已提交。
```

核心验收结果：

- 用户输入 `我想学习 HashMap` 可以创建 LearningSession。
- 用户输入 `我还是不懂` 可以复用同一个 LearningSession。
- 第二次反馈后 topic 仍保持 `HashMap`。
- LearningSessionStep 能记录两次交互步骤。
- `/api/tutor-agent/chat` 返回结构化结果，而不是纯文本。
- 后端编译通过。
- 前端构建通过。

### Phase 2：Teaching Strategy Layer

目标：

```text
让 Agent 不只是知道用户哪里不会，
还要知道应该怎么教。
```

Phase 2 的重点不是增加很多页面，而是把教学策略选择从 `TutorAgentServiceImpl` 中抽出来，形成独立的 Teaching Strategy Layer。

建议新增：

```text
TeachingStrategyService
```

输入：

- intent
- 用户输入
- LearningSession
- 当前 topic
- 最近 LearningSessionStep
- 用户反馈信号

输出：

- teachingStrategy
- strategySource
- nextAction

预留策略类型：

- `concept_first`：概念优先
- `example_first`：案例优先
- `source_code_first`：源码优先
- `prerequisite_first`：前置知识补齐
- `practice_first`：练习优先
- `debug_misconception`：误区纠正
- `summary_review`：总结复盘

每次策略选择都必须输出 `strategySource`，说明为什么选择这个策略。

示例：

```json
{
  "teachingStrategy": "prerequisite_first",
  "strategySource": [
    "用户连续两次反馈仍不理解",
    "当前主题为 HashMap",
    "可能存在并发基础或 Map 数据结构前置知识缺口"
  ],
  "nextAction": "先补齐必要前置知识，再重新解释 HashMap 当前问题"
}
```

Phase 2 不做：

- 不做 Memory 生命周期。
- 不做 Knowledge Map 决策。
- 不做 RAG 检索优化。
- 不做完整 Evaluation。
- 不引入多 Agent 框架。
- 不引入 Redis / MQ / 微服务。

当前状态：

```text
已完成。
```

实现内容：

- 新增 `TeachingStrategyService`，统一输出 `teachingStrategy`、`strategySource` 与 `nextAction`。
- 支持 `concept_first`、`example_first`、`source_code_first`、`prerequisite_first`、`practice_first`、`debug_misconception`、`summary_review` 七种策略。
- `TutorAgentServiceImpl` 保留 LearningSession 创建、复用和 Step 记录流程，并委托 Teaching Strategy Layer 选择策略。
- 同一 LearningSession 中，上一次为 `concept_difficulty` 或 `reflection` 的理解困难 Step 后，下一次“我还是不懂”会切换为 `prerequisite_first`；策略原因会说明连续理解困难和可能的前置知识缺口。
- `LearningSessionStep.strategy_source` 持久化策略原因；查询 active LearningSession 时会恢复最近一次原因，Chat 页面展示中文策略名称和原因列表。

验收结果：

- “我想学习 HashMap” → `concept_first`。
- “能不能举个例子解释 HashMap” → `example_first`。
- “HashMap 源码里 put 是怎么实现的” → `source_code_first`。
- 第一次“我还是不懂” → `debug_misconception`，并复用原有 topic。
- 连续第二次“我还是不懂” → `prerequisite_first`。
- “做题练一下” → `practice_first`。
- “总结一下” → `summary_review`。

### Phase 3：Knowledge Map 与 RAG 决策参与

当前说明：

```text
之前独立的 Knowledge Graph 实现已经从 Phase 1 中移除。
```

移除原因：

- 当前知识图谱更像一个展示页面。
- 它没有真正影响 Agent 的诊断、教学策略和学习路径决策。
- 继续保留会让项目显得功能多但闭环弱。

后续重新规划方向：

```text
不要重做一个“知识图谱页面”，
而是做一个能参与 Agent 决策的 Knowledge Map。
```

未来 Knowledge Map 应该参与：

- 前置知识判断
- 学习路径推荐
- 下一步学习决策
- 知识点依赖解释
- Teaching Strategy 选择依据

RAG 的定位：

```text
Learning Knowledge Retrieval
```

RAG 服务于：

- 教学解释
- 举例
- 纠错
- 总结
- 引用来源

RAG 不应该喧宾夺主，也不应该把项目变成知识库问答系统。

Phase 3 实现范围：

- 新增 `knowledge_map_dependency`，显式保存知识点的有向直接前置依赖，而不复用仅表示层级的 `parent_id`。
- 新增 `KnowledgeMapService`：按当前 topic 解析知识点，读取直接前置项，并结合当前用户 `learning_record` 返回掌握度低于 70 的前置知识。
- 为 Java `HashMap` 预置 `基础语法`、`面向对象`、`集合框架` 三个直接前置项。
- 将 Knowledge Map 上下文传给 Teaching Strategy Layer。连续理解困难或用户明确要求补基础时，`prerequisite_first` 的 `strategySource` 与 `nextAction` 会引用未掌握的前置项。
- `POST /api/tutor-agent/chat` 与 active LearningSession 查询返回 `knowledgeMap`；Chat 仅以标签展示建议补齐的前置项。

Phase 3 明确不做：

- 不重做独立知识图谱页面。
- 不做 RAG 检索、召回或排序优化。
- 不做 Memory 生命周期、Evaluation、Redis、MQ、微服务或多 Agent 框架。

Phase 3 验收标准：

- 执行 `backend/src/main/resources/db/stage13-knowledge-map.sql` 后，`HashMap` 能解析到预置的直接前置依赖。
- 对掌握度低于 70 的前置项，chat 响应和 active LearningSession 返回 `knowledgeMap.unmetPrerequisites`。
- 第一次“我还是不懂”仍使用 `debug_misconception`；连续理解困难后使用 `prerequisite_first`，策略原因明确包含前置知识缺口与知识地图结果。
- 掌握度不低于 70 的前置项不会出现在未掌握列表中。
- 后端单元测试、后端编译和前端构建通过。

### Phase 4：Memory 生命周期

目标：

```text
让 Agent 能长期理解用户，但避免长期记忆污染决策。
```

v1 Memory 只保留三类长期记忆：

- `preference`：学习偏好
- `difficulty_pattern`：困难模式
- `misconception`：长期误解

Memory 必须支持：

- 产生
- 更新
- 置信度变化
- 过期
- 抑制
- 删除

Phase 4 不应该一开始就做复杂记忆系统。重点是记忆生命周期和可控性。

### Phase 5：Evaluation 与工程治理

目标：

```text
证明 Agent 不只是能跑，
还要能评估、能观察、能持续优化。
```

评估维度：

- Agent 质量
- 学习效果
- 用户体验
- 系统稳定性

Agent 质量指标：

- 策略选择准确率
- 工具选择准确率
- 决策有效性
- 失败恢复成功率

学习效果指标：

- 知识增益
- 保持率
- 迁移能力
- 复习效果

用户体验指标：

- 用户接受率
- 反馈有用率
- Session 完成率
- 持续使用率

工程治理指标：

- AI 调用耗时
- AI 调用失败率
- 成本统计
- 接口错误率
- 慢请求

## 4. 核心架构决策

### 4.1 ChatSession

ChatSession 表示用户和 AI 的对话上下文。

在当前代码中，它由以下表承载：

- `conversation`
- `chat_history`

ChatSession 负责保存对话连续性，但不负责学习任务生命周期。

### 4.2 LearningSession

LearningSession 表示一次具体学习任务。

例如：

- 学习 HashMap
- 复习 JVM GC
- 准备 Spring 事务
- 完成某个知识点练习

LearningSession 需要记录：

- 当前学习目标
- 当前主题
- 当前状态
- 当前步骤类型
- 教学策略
- 下一步行动建议
- 已执行步骤
- 用户反馈

状态值：

```text
CREATED
DIAGNOSING
PLANNING
TEACHING
PRACTICE
REFLECTION
COMPLETED
```

后续如果新增关闭接口，可以增加：

```text
CLOSED
```

### 4.3 LearnerModelService

LearnerModelService 是聚合服务，不是数据库表。

它后续应该从以下数据中构建学习者快照：

- 用户 Profile
- Chat 历史
- 学习记录
- 练习结果
- 后续 Knowledge Map
- 后续 Memory

Phase 1 只通过返回结构和会话记录预留接口形态，不提前新增 `learner_model` 表。

### 4.4 Tutor Agent

Phase 1 中 Tutor Agent 是轻量编排层：

- 复用现有 AI Chat 和 Orchestrator。
- 创建或复用 active LearningSession。
- 记录 Agent Step。
- 返回结构化输出。

它现在不是完整自主 Agent，也不应该在 Phase 1 变成大型规则系统。

Phase 2 开始，Tutor Agent 应该逐步把教学策略选择委托给 Teaching Strategy Layer。

## 5. 当前已实现接口

### 5.1 Tutor Agent Chat

```text
POST /api/tutor-agent/chat
```

请求示例：

```json
{
  "conversationId": 1,
  "message": "我还是不理解 HashMap 为什么线程不安全",
  "learningSessionId": 10
}
```

说明：

- `conversationId` 必填。
- `message` 必填。
- `learningSessionId` 可选。
- 如果不传 `learningSessionId`，后端优先复用当前 conversation 下的 active LearningSession。

响应示例：

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
    "Current topic: HashMap",
    "User message indicates unresolved understanding"
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

### 5.2 查询当前 active LearningSession

```text
GET /api/learning-sessions/active?conversationId=1
```

用途：

- 前端切换 ChatSession 后恢复当前 active LearningSession。
- Chat 页面展示当前学习任务状态。

没有 active LearningSession 时返回 `null`。

## 6. 后续预留接口

### 6.1 LearningSession 后续接口

保留到后续阶段：

```text
GET  /api/learning-sessions/{id}
POST /api/learning-sessions/{id}/confirm-action
POST /api/learning-sessions/{id}/close
GET  /api/learning-sessions/{id}/report
```

### 6.2 Memory 后续接口

保留到 Phase 4：

```text
GET    /api/learner-memories
PATCH  /api/learner-memories/{id}/suppress
DELETE /api/learner-memories/{id}
```

## 7. Phase 1 验收标准

Phase 1 满足以下条件即可验收：

- Chat 消息可以创建或复用 active LearningSession。
- `我还是不懂` 这类反馈型消息沿用 active LearningSession 的 topic，不覆盖成弱代词。
- LearningSession 和 LearningSessionStep 能持久化。
- `/api/tutor-agent/chat` 返回结构化 Agent 输出，而不是纯文本。
- Chat UI 展示 active LearningSession 的目标、状态、策略占位和下一步行动。
- 现有 Chat、知识资料库、题库、学习记录功能不被破坏。
- 前端 build 通过。
- 后端 compile/test 已执行，或明确记录本地环境阻塞原因。
- Windows 本地后端启动使用 `backend/run.cmd`。
- Maven 依赖缓存使用 `backend/.m2/repository`。

当前状态：

```text
已验收。
```

## 8. 本地运行方式

### 8.1 执行数据库脚本

Phase 1 需要执行：

```text
backend/src/main/resources/db/stage12-learning-session.sql
```

PowerShell 示例：

```powershell
Get-Content -Raw "D:\AI-Tutor\backend\src\main\resources\db\stage12-learning-session.sql" | & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p ai_tutor
```

说明：

PowerShell 不支持传统 CMD 的 `<` 输入重定向写法，因此推荐使用 `Get-Content -Raw ... | mysql`。

### 8.2 启动后端

推荐将本地配置写入 `backend/.env`：

```text
SPRING_DATASOURCE_PASSWORD=your_mysql_password
DEEPSEEK_API_KEY=your_deepseek_api_key
```

启动：

```powershell
cd D:\AI-Tutor\backend
.\run.cmd
```

说明：

- `run.cmd` 会读取 `backend/.env`。
- `run.cmd` 会使用项目内 Maven 缓存。
- Windows 本地开发不推荐优先使用 `spring-boot:run`。

### 8.3 启动前端

```powershell
cd D:\AI-Tutor\frontend
npm install
npm run dev
```

如果 PowerShell 执行策略拦截 `npm.ps1`，可以使用：

```powershell
npm.cmd run dev
```

默认访问地址：

```text
http://localhost:5173
```

## 9. 下一步建议

下一步进入 Phase 4：

```text
Memory 生命周期 MVP
```

Phase 4 应先定义可删除、可过期、可抑制的 Memory 生命周期，再决定长期记忆的扩展范围。
