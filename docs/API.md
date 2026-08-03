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
  "count": 1
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
      "knowledgePointId": 10,
      "knowledgePointName": "HashMap",
      "questionType": "single_choice",
      "content": "HashMap 中 key 的主要作用是什么？",
      "options": ["A. 快速定位 value", "B. 保存线程状态", "C. 控制循环次数", "D. 编译 Java 文件"],
      "answer": "A",
      "analysis": "HashMap 通过 key 计算位置，从而快速找到对应 value。",
      "difficulty": "medium",
      "source": "ai",
      "createTime": "2026-08-03T15:20:00"
    }
  ]
}
```

说明：

- `questionType` 当前支持 `single_choice`、`true_false`、`short_answer`，默认 `single_choice`。
- `difficulty` 当前支持 `easy`、`medium`、`hard`，默认 `medium`。
- `count` 范围为 1-5，默认 3。
- 后端会校验 AI 返回 JSON 结构，校验通过后才保存题目。

### 8.2 查询题目列表

```text
GET /api/questions?knowledgePointId=10&questionType=single_choice&difficulty=medium
```

说明：

- 查询参数都可选。
- 返回字段同生成题目接口。

### 8.3 查询单个题目

```text
GET /api/questions/{questionId}
```

说明：

- 返回字段同生成题目接口。

### 8.4 提交答案

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
    "answerRecordId": 3001,
    "questionId": 2001,
    "correct": true,
    "score": 100,
    "correctAnswer": "A",
    "analysis": "HashMap 通过 key 计算位置，从而快速找到对应 value。",
    "feedback": "回答正确。\n解析：HashMap 通过 key 计算位置，从而快速找到对应 value。",
    "learningStatus": "mastered",
    "masteryLevel": 100
  }
}
```

说明：

- 客观题：`single_choice`、`true_false` 由后端直接判分。
- 主观题：`short_answer` 调用 AI 批改，并从反馈中的得分更新掌握程度。
- 答题结果会保存到 `answer_record`。
- 答题后会更新 `learning_record`，用于后续学习分析阶段。

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
  "message": "上传成功",
  "data": {
    "documentId": 3001,
    "processStatus": "completed",
    "chunkCount": 8
  }
}
```

说明：

- 当前阶段支持 `txt`、`md`、`markdown`、`pdf` 资料。
- 上传后同步完成文本解析和分块，分块存入 MySQL。
- PDF 使用 PDFBox 提取文本；扫描版图片 PDF 可能无法提取有效内容。
- Office 和向量化检索后续阶段扩展。

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
      "fileName": "java-note.md",
      "fileType": "md",
      "processStatus": "completed",
      "chunkCount": 8,
      "uploadTime": "2026-08-02 10:00:00"
    }
  ]
}
```

### 10.3 查询文档详情

```text
GET /api/documents/{documentId}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 3001,
    "fileName": "java-note.md",
    "fileType": "md",
    "processStatus": "completed",
    "chunkCount": 8,
    "preview": "HashMap is a Java collection that stores key-value pairs...",
    "uploadTime": "2026-08-02 10:00:00"
  }
}
```

### 10.4 查询文档切片

```text
GET /api/documents/{documentId}/chunks
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 9001,
      "documentId": 3001,
      "chunkIndex": 0,
      "chunkText": "HashMap is a Java collection...",
      "createTime": "2026-08-02 10:00:00"
    }
  ]
}
```

### 10.5 重新处理文档

```text
POST /api/documents/{documentId}/reprocess
```

说明：

- 会读取已保存的原始文件，删除旧 chunk 后重新解析和切片。
- 如果原始文件不存在或解析失败，文档状态会更新为 `failed`。

### 10.6 删除文档

```text
DELETE /api/documents/{documentId}
```

说明：

- 删除当前用户自己的文档元数据、chunk 和本地存储文件。

### 10.7 知识库问答

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
    "conversationId": 1003,
    "answer": "根据你上传的教材内容，HashMap 是一种基于哈希表实现的键值对集合...",
    "sources": [
      {
        "documentId": 3001,
        "fileName": "java-note.md",
        "chunkIndex": 5,
        "snippet": "HashMap 是基于哈希表的键值对集合..."
      }
    ]
  }
}
```

说明：

- `conversationId` 必须属于当前用户，且会话 `mode` 为 `rag`。
- `documentIds` 可选；不传时检索当前用户所有已处理完成的文档。
- 当前阶段使用 MySQL 文本 chunk 做关键词检索，返回 TopK 来源片段。
- 如果资料中没有找到足够依据，接口会直接返回明确提示，不调用 AI 编造答案。

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
    "learnedCount": 3,
    "masteredCount": 1,
    "inProgressCount": 2,
    "averageMasteryLevel": 68,
    "totalStudyTime": 42,
    "answeredQuestionCount": 8,
    "correctAnswerCount": 5,
    "answerAccuracy": 63,
    "chatMessageCount": 18,
    "weakKnowledgePoints": [
      {
        "knowledgePointId": 6,
        "knowledgePointName": "HashMap",
        "subject": "Java",
        "masteryLevel": 55,
        "answerAccuracy": 50,
        "reason": "掌握度低于 70%；答题正确率低于 70%"
      }
    ],
    "suggestions": [
      "优先复习：HashMap。",
      "当前答题正确率低于 70%，建议先复盘错题解析，再做同知识点变式题。"
    ],
    "nextActions": [
      "用教学模式重新学习：HashMap。",
      "围绕 HashMap 各完成 2 道客观题和 1 道简答题。"
    ]
  }
}
```

说明：

- 当前阶段不新增学习分析表，接口从 `learning_record`、`answer_record`、`chat_history` 和 `student_profile` 实时汇总。
- `weakKnowledgePoints` 会综合掌握度和答题正确率，默认将低于 70% 的知识点视为薄弱项。
- `suggestions` 和 `nextActions` 是规则生成的 MVP 版本，不调用 AI。

### 11.2 查询知识点掌握明细

```text
GET /api/analysis/knowledge-points
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "knowledgePointId": 6,
      "knowledgePointName": "HashMap",
      "subject": "Java",
      "learningStatus": "learning",
      "masteryLevel": 55,
      "studyTime": 12,
      "answeredQuestionCount": 3,
      "correctAnswerCount": 1,
      "answerAccuracy": 33,
      "averageScore": 40,
      "updateTime": "2026-08-02 10:00:00"
    }
  ]
}
```

说明：

- 该接口会合并教学记录和答题记录，用于展示每个知识点的掌握度、学习时长和答题表现。
- 排序优先展示掌握度或正确率较低的知识点，方便直接定位复习对象。

### 11.3 查询近期答题分析

```text
GET /api/analysis/recent-answers?limit=10
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "answerRecordId": 8001,
      "questionId": 7001,
      "knowledgePointId": 6,
      "knowledgePointName": "HashMap",
      "questionType": "single_choice",
      "difficulty": "medium",
      "questionContent": "HashMap 的 key 有什么要求？",
      "userAnswer": "A",
      "correct": false,
      "score": 0,
      "feedbackPreview": "回答不正确。正确答案是 B...",
      "createTime": "2026-08-02 10:00:00"
    }
  ]
}
```

说明：

- `limit` 默认为 10，最大 50。
- 返回内容用于学习分析页展示最近错题和反馈摘要。

### 11.4 生成学习计划

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
    "period": "week",
    "goal": "提升 Java 集合和多线程",
    "estimatedDays": 7,
    "focusKnowledgePoints": ["HashMap", "ArrayList"],
    "steps": [
      "第 1 天：明确目标「提升 Java 集合和多线程」，浏览 HashMap、ArrayList 的知识点结构。",
      "第 2-3 天：使用教学模式重学 HashMap、ArrayList，把不懂的问题继续追问。"
    ],
    "planContent": "1. 第 1 天：明确目标「提升 Java 集合和多线程」，浏览 HashMap、ArrayList 的知识点结构。\n2. 第 2-3 天：使用教学模式重学 HashMap、ArrayList，把不懂的问题继续追问。"
  }
}
```

说明：

- `period` 当前支持 `week` 和 `month`，其他值会按 `week` 处理。
- `goal` 可不传；不传时优先使用学习档案中的 `learningGoal`。
- 学习计划会优先围绕薄弱知识点生成；没有薄弱项时使用学习方向作为重点。
