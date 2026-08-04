# AI Tutor 前端工作台改造计划

## 1. 当前目标

前端从单页功能集合升级为多页面学习工作台，先服务已经跑通的后端 MVP 闭环：

```text
注册登录 → 学习档案 → 会话聊天 → 教学模式 → 出题练习 → 资料问答 → 学习分析 → Agent 建议
```

本阶段采用 Stitch 生成图作为视觉和布局参考，不照搬生成代码。主题审美先以 Lumina 浅色方案为主方向，Glacier 深色方案仅保留为后期夜间模式方向，具体配色细节和审美主题后续单独讨论确定。

## 2. 技术选型

| 类型 | 选型 |
|---|---|
| 前端框架 | Vue3 |
| 构建工具 | Vite |
| 路由 | Vue Router |
| 状态管理 | Pinia |
| HTTP 请求 | Axios |
| UI 组件 | Element Plus |
| Markdown 展示 | MarkdownIt |

## 3. 页面结构

核心页面按学习工作台拆分：

- `Dashboard`：突出继续聊天、开始学习、查看建议 2-3 个高频动作，不做卡片墙。
- `AI Chat`：作为主力页面，聊天区更宽，右侧只放上下文和档案摘要。
- `Learning Path`：展示知识点路径，从知识点直接进入教学或练习。
- `Practice`：围绕知识点生成题目、答题和查看反馈。
- `Knowledge Library`：突出资料问答和资料来源可信度，不做成文件管理器。
- `Learning Analysis`：展示学习概览、薄弱点、近期答题和学习计划。
- `Agent Suggestions`：突出建议原因和影响，按钮保持克制。
- `Profile`：维护学习方向、目标、水平和偏好。

## 4. API 对接范围

只对接当前后端已实现接口：

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/users/me`
- `GET /api/profile`
- `PUT /api/profile`
- `POST /api/conversations`
- `GET /api/conversations`
- `GET /api/conversations/{conversationId}/messages`
- `POST /api/ai/chat`
- `GET /api/knowledge-points/tree`
- `POST /api/teaching/start`
- `POST /api/teaching/evaluate`
- `GET /api/teaching/records`
- `POST /api/questions/generate`
- `GET /api/questions`
- `POST /api/questions/{questionId}/answer`
- `POST /api/documents/upload`
- `GET /api/documents`
- `GET /api/documents/{documentId}`
- `GET /api/documents/{documentId}/chunks`
- `POST /api/documents/{documentId}/reprocess`
- `DELETE /api/documents/{documentId}`
- `POST /api/ai/rag/chat`
- `GET /api/analysis/overview`
- `GET /api/analysis/knowledge-points`
- `GET /api/analysis/recent-answers`
- `POST /api/study-plans/generate`
- `POST /api/agents/suggestions/generate`
- `GET /api/agents/suggestions`
- `GET /api/agents/suggestions/{suggestionId}/events`
- `POST /api/agents/suggestions/{suggestionId}/confirm`
- `POST /api/agents/suggestions/{suggestionId}/complete`
- `POST /api/agents/suggestions/{suggestionId}/dismiss`

认证方式：

```text
Authorization: Bearer <token>
```

## 5. 状态管理

继续使用 Pinia：

- `auth`：Token、当前用户、登录状态。
- `workspace`：学习档案、会话列表、当前会话、消息列表、发送状态。
- 页面本地状态：知识点、教学、题目、资料、分析、Agent 建议先保留在对应页面内，后期出现跨页面共享需求再抽 store。

## 6. 开发顺序

```text
更新文档基准
  → 拆出工作台布局和多页面路由
  → 重构 Dashboard / AI Chat 主体验
  → 接上 Learning Path 与 Practice
  → 接上 Knowledge Library / Analysis / Agent
  → 调整 Lumina 浅色样式
  → 构建和浏览器预览验证
```

## 7. 暂不做

- 不接入新的后端能力。
- 不实现管理员后台。
- 不扩展复杂 RAG 检索设置。
- 不做完整暗色主题，只保留 Glacier 后续方向。
- 不提前定最终品牌视觉、插画、动效和主题审美。
