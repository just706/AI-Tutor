# AI Tutor 接口文档

## 1. 接口规范

### 1.1 基础路径

```text
/api
```

### 1.2 数据格式

请求和响应默认使用 JSON。

### 1.3 通用响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

### 1.4 通用错误码

| code | 说明 |
|---|---|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未登录或 Token 失效 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器错误 |
| 600 | AI 服务调用失败 |

### 1.5 认证方式

登录成功后，前端在请求头中携带 Token。

```text
Authorization: Bearer <token>
```

## 2. 用户接口

### 2.1 用户注册

```text
POST /api/auth/register
```

请求参数：

```json
{
  "username": "student001",
  "password": "123456"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "注册成功",
  "data": {
    "userId": 1,
    "username": "student001"
  }
}
```

### 2.2 用户登录

```text
POST /api/auth/login
```

请求参数：

```json
{
  "username": "student001",
  "password": "123456"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "token-value",
    "user": {
      "id": 1,
      "username": "student001",
      "role": "student"
    }
  }
}
```

### 2.3 获取当前用户信息

```text
GET /api/users/me
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "student001",
    "nickname": "小明",
    "role": "student"
  }
}
```

## 3. 学习档案接口

### 3.1 获取学习档案

```text
GET /api/profile
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "learningDirection": "Java",
    "learningGoal": "就业",
    "currentLevel": "基础",
    "learningPreference": "案例讲解、循序渐进"
  }
}
```

### 3.2 保存学习档案

```text
PUT /api/profile
```

请求参数：

```json
{
  "learningDirection": "Java",
  "learningGoal": "就业",
  "currentLevel": "基础",
  "learningPreference": "案例讲解、循序渐进"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "保存成功",
  "data": true
}
```

## 4. 会话接口

### 4.1 创建会话

```text
POST /api/conversations
```

请求参数：

```json
{
  "title": "Java 集合学习",
  "mode": "chat"
}
```

说明：

- `title` 必填，最长 128 个字符。
- `mode` 可选，未传时默认为 `chat`，当前支持 `chat`、`teaching`、`rag`。

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "conversationId": 1001
  }
}
```

### 4.2 查询会话列表

```text
GET /api/conversations
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1001,
      "title": "Java 集合学习",
      "mode": "chat",
      "updateTime": "2026-08-02 10:00:00"
    }
  ]
}
```

### 4.3 查询会话消息

```text
GET /api/conversations/{conversationId}/messages
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "role": "user",
      "messageContent": "什么是 HashMap？",
      "createTime": "2026-08-02 10:00:00"
    },
    {
      "role": "assistant",
      "messageContent": "HashMap 是 Java 中常用的键值对集合...",
      "createTime": "2026-08-02 10:00:05"
    }
  ]
}
```

## 5. AI 聊天接口

### 5.1 发送普通聊天消息

```text
POST /api/ai/chat
```

请求参数：

```json
{
  "conversationId": 1001,
  "message": "什么是 HashMap？"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "HashMap 是 Java 中用于存储键值对的数据结构..."
  }
}
```

说明：

- 该接口会校验会话是否属于当前登录用户。
- 调用成功后会保存用户消息和 AI 回复。
- 调用失败时会保留用户消息，返回 `code: 600`，并记录 AI 调用日志。

### 5.2 发送流式聊天消息

```text
POST /api/ai/chat/stream
```

说明：

- 该接口用于优化 AI 回答等待体验。
- 可以使用 SSE 或 WebSocket 实现。
- MVP 阶段可先实现普通非流式接口。

## 6. AI 教学模式接口

### 6.1 开始教学

```text
POST /api/teaching/start
```

请求参数：

```json
{
  "knowledgePointId": 10
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "conversationId": 1002,
    "knowledgePointId": 10,
    "knowledgePointName": "HashMap",
    "teachingContent": "我们先学习 HashMap 的核心概念...",
    "learningStatus": "in_progress",
    "masteryLevel": 0
  }
}
```

说明：

- 该接口会创建一个 `mode=teaching` 的会话。
- AI 回复中会包含知识讲解和理解检查问题。
- 调用成功后会保存会话消息，并将学习记录更新为 `in_progress`。

### 6.2 提交理解检查答案

```text
POST /api/teaching/evaluate
```

请求参数：

```json
{
  "conversationId": 1002,
  "knowledgePointId": 10,
  "studentAnswer": "HashMap 通过 key 找 value，适合快速查询。"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "conversationId": 1002,
    "knowledgePointId": 10,
    "feedback": "是否基本正确：部分正确\n得分：75\n回答优点：...",
    "learningStatus": "completed",
    "masteryLevel": 75
  }
}
```

说明：

- 该接口会校验教学会话是否属于当前登录用户。
- AI 会根据最近教学上下文评价学生回答。
- 系统会从 AI 反馈中的得分更新 `learning_record.mastery_level`。
- 学习状态取值：`in_progress`、`completed`、`mastered`。

### 6.3 查询当前用户学习记录

```text
GET /api/teaching/records
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "knowledgePointId": 10,
      "knowledgePointName": "HashMap",
      "subject": "Java",
      "learningStatus": "completed",
      "masteryLevel": 75,
      "studyTime": 6,
      "updateTime": "2026-08-03T14:30:00"
    }
  ]
}
```

## 7. 知识点接口

### 7.1 查询知识点树

```text
GET /api/knowledge-points/tree?subject=Java
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "subject": "Java",
      "name": "Java",
      "parentId": 0,
      "sortOrder": 1,
      "children": [
        {
          "id": 2,
          "subject": "Java",
          "name": "基础语法",
          "parentId": 1,
          "sortOrder": 10,
          "children": []
        }
      ]
    }
  ]
}
```

### 7.2 新增知识点

```text
POST /api/admin/knowledge-points
```

请求参数：

```json
{
  "subject": "Java",
  "name": "集合",
  "parentId": 1,
  "sortOrder": 3
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 20,
    "subject": "Java",
    "name": "集合",
    "parentId": 1,
    "sortOrder": 3,
    "children": []
  }
}
```

说明：

- 需要当前登录用户角色为 `admin`。

### 7.3 修改知识点

```text
PUT /api/admin/knowledge-points/{id}
```

请求参数：

```json
{
  "subject": "Java",
  "name": "集合框架",
  "parentId": 1,
  "sortOrder": 30
}
```

说明：

- 需要当前登录用户角色为 `admin`。
- 不允许将知识点父级设置为自己或自己的子节点。

### 7.4 删除知识点

```text
DELETE /api/admin/knowledge-points/{id}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": true
}
```

说明：

- 需要当前登录用户角色为 `admin`。
- 有子节点或已有学习记录的知识点不能删除。

## 8. 题目接口

### 8.1 AI 生成题目

```text
POST /api/questions/generate
```

请求参数：

```json
{
  "knowledgePointId": 10,
  "questionType": "single_choice",
  "difficulty": "medium",
  "count": 3
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 2001,
      "content": "以下关于 Java 接口的说法正确的是？",
      "options": ["接口不能被实现", "接口可以定义抽象方法", "接口只能有成员变量", "接口不能用于多态"],
      "answer": "接口可以定义抽象方法",
      "analysis": "接口用于定义规范，类可以实现接口中的方法。",
      "difficulty": "medium"
    }
  ]
}
```

### 8.2 提交答案

```text
POST /api/questions/{questionId}/answer
```

请求参数：

```json
{
  "answer": "接口可以定义抽象方法"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "correct": true,
    "score": 100,
    "analysis": "回答正确。接口可以定义抽象方法，由实现类完成具体实现。"
  }
}
```

## 9. 学习记录接口

### 9.1 查询学习记录

```text
GET /api/learning-records
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "knowledgePointId": 10,
      "knowledgePointName": "接口",
      "learningStatus": "learning",
      "masteryLevel": 60,
      "studyTime": 35
    }
  ]
}
```

### 9.2 更新学习记录

```text
PUT /api/learning-records/{knowledgePointId}
```

请求参数：

```json
{
  "learningStatus": "completed",
  "masteryLevel": 85,
  "studyTime": 60
}
```

## 10. RAG 文档接口

### 10.1 上传文档

```text
POST /api/documents/upload
```

请求类型：

```text
multipart/form-data
```

字段：

| 字段 | 说明 |
|---|---|
| file | 上传文件 |

响应示例：

```json
{
  "code": 200,
  "message": "上传成功，正在处理",
  "data": {
    "documentId": 3001,
    "processStatus": "pending"
  }
}
```

### 10.2 查询文档列表

```text
GET /api/documents
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 3001,
      "fileName": "Java教材.pdf",
      "fileType": "pdf",
      "processStatus": "completed",
      "uploadTime": "2026-08-02 10:00:00"
    }
  ]
}
```

### 10.3 知识库问答

```text
POST /api/ai/rag/chat
```

请求参数：

```json
{
  "conversationId": 1003,
  "question": "根据我的 Java 教材解释 HashMap",
  "documentIds": [3001]
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "根据你上传的教材内容，HashMap 是一种基于哈希表实现的键值对集合...",
    "sources": [
      {
        "documentId": 3001,
        "fileName": "Java教材.pdf",
        "chunkIndex": 5
      }
    ]
  }
}
```

## 11. 学习分析接口

### 11.1 查询学习概览

```text
GET /api/analysis/overview
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "learnedCount": 12,
    "weakKnowledgePoints": ["集合", "多线程"],
    "averageMasteryLevel": 68
  }
}
```

### 11.2 生成学习计划

```text
POST /api/study-plans/generate
```

请求参数：

```json
{
  "period": "week",
  "goal": "提升 Java 集合和多线程"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "title": "Java 提升计划",
    "planContent": "1. 复习集合框架；2. 学习线程基础；3. 完成 20 道练习题。"
  }
}
```
