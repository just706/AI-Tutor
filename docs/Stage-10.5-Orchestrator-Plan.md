# 阶段 10.5：AI Chat 主导的学习闭环改造

## 1. 目标

把 AI Chat 从普通问答入口升级为学习流程主入口。用户可以在 Chat 中自然提问，系统在回答后给出可操作的学习动作卡片，帮助用户进入教学、练习、学习路径、学习分析或 Agent 建议。

## 2. 本阶段范围

- 新增后端编排接口：`POST /api/ai/orchestrator/chat`。
- 编排接口先复用普通 AI 聊天答案，不重新实现一套回答逻辑。
- 根据用户消息做规则型意图识别，识别学习、练习、路径、分析等场景。
- 匹配数据库中的 `knowledge_point`，优先把动作绑定到标准知识点 ID。
- 前端 Chat 展示动作卡片，并支持跳转到对应页面。
- Learning Path 支持通过 `knowledgePointId` query 定位知识点。
- Learning Path 在 Chat 主题未命中标准知识库时，可调用 AI 生成临时个人路径。

## 3. 暂不做

- 不做联网搜索 Agent。
- 不做自动执行高影响动作。
- 不新增 Python AI 服务。
- 不接入向量数据库。
- 不自动创建个人学习路径表。
- 不把 Learning Path / Practice / Analysis 页面删除。
- 不把 AI 生成路径直接写入 `knowledge_point`。

## 4. 接口设计

```text
POST /api/ai/orchestrator/chat
```

请求体与普通聊天保持一致：

```json
{
  "conversationId": 1001,
  "message": "我想学 HashMap，讲完以后给我出题"
}
```

响应：

```json
{
  "answer": "普通 AI Chat 生成的回答内容",
  "intent": "practice",
  "matchedKnowledgePoint": {
    "id": 6,
    "subject": "Java",
    "name": "HashMap",
    "parentId": 4,
    "sortOrder": 10,
    "children": []
  },
  "actions": [
    {
      "actionType": "open_practice",
      "title": "围绕 HashMap 练习",
      "description": "进入练习页，使用当前知识点生成针对性题目。",
      "label": "进入练习",
      "routeName": "practice",
      "impactLevel": "medium",
      "payload": {
        "knowledgePointId": 6,
        "knowledgePointName": "HashMap",
        "subject": "Java",
        "topic": "HashMap"
      }
    }
  ]
}
```

## 5. 验证

- 后端编译通过。
- 前端构建通过。
- Chat 中普通问题仍可正常得到 AI 答案。
- 命中知识点时，动作卡片可跳转到 Learning Path 或 Practice。
- 未命中知识点时，动作卡片携带 Chat `topic`，Learning Path 可生成 `ai_generated` 临时路径。
