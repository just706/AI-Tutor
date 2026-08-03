# AI Tutor 前端 MVP 开发计划

## 1. 文档目标

本文档用于指导 AI Tutor 前端 MVP 开发。

当前目标是把已经跑通的后端 MVP 闭环做成可操作界面：

```text
注册登录 → 学习档案 → 会话 → AI 聊天 → 历史消息
```

本阶段先保证功能可用、流程清晰、联调稳定。主题审美、视觉风格和更细的界面质感后续单独讨论确定。

## 2. 技术选型

| 类型 | 选型 |
|---|---|
| 前端框架 | Vue3 |
| 构建工具 | Vite |
| 路由 | Vue Router |
| 状态管理 | Pinia |
| HTTP 请求 | Axios |
| UI 组件 | Element Plus |
| Markdown 展示 | Markdown 渲染组件 |

## 3. MVP 页面范围

MVP 阶段只做以下页面：

- 登录页。
- 注册页。
- 学习档案页。
- AI 聊天主界面。
- 会话列表。
- 历史消息展示。

暂不开发：

- 主题审美细化。
- 知识点教学。
- AI 出题。
- RAG 知识库。
- 学习分析。
- 管理员后台。

## 4. API 对接范围

前端 MVP 只对接当前后端已实现接口：

| 功能 | 接口 |
|---|---|
| 注册 | `POST /api/auth/register` |
| 登录 | `POST /api/auth/login` |
| 当前用户 | `GET /api/users/me` |
| 查询学习档案 | `GET /api/profile` |
| 保存学习档案 | `PUT /api/profile` |
| 创建会话 | `POST /api/conversations` |
| 会话列表 | `GET /api/conversations` |
| 会话消息 | `GET /api/conversations/{conversationId}/messages` |
| AI 聊天 | `POST /api/ai/chat` |

认证方式：

```text
Authorization: Bearer <token>
```

后端地址建议通过环境变量配置：

```text
VITE_API_BASE_URL=http://localhost:8080/api
```

## 5. 状态管理

建议使用 Pinia 管理以下状态：

- `auth`：Token、当前用户、登录状态。
- `profile`：学习方向、学习目标、当前水平、学习偏好。
- `conversation`：会话列表、当前会话 ID。
- `chat`：当前会话消息、发送中状态、错误信息。

Token 初期可以保存在 `localStorage`，请求拦截器自动附加到受保护接口。

## 6. 开发顺序

建议按以下顺序开发：

```text
初始化 Vue3 项目
  ↓
配置路由、Pinia、Axios
  ↓
封装 API 请求和 Token 拦截器
  ↓
实现登录和注册
  ↓
实现学习档案维护
  ↓
实现会话列表和创建会话
  ↓
实现 AI 聊天和历史消息展示
  ↓
联调后端 MVP 测试脚本
```

## 7. 交互要求

MVP 阶段前端需要覆盖基础交互状态：

- 登录失败、注册失败要展示错误提示。
- 未登录访问业务页面时跳转登录页。
- AI 回复等待期间展示发送中状态。
- 会话为空时展示空状态。
- 网络或 AI 调用失败时展示可理解错误。
- 长回答使用 Markdown 展示，保证可阅读。

## 8. 验收标准

前端 MVP 完成后应满足：

- 用户可以注册和登录。
- 登录后可以保存和读取学习档案。
- 用户可以创建会话并查看会话列表。
- 用户可以在会话中发送问题并看到 AI 回复。
- 用户可以刷新页面后继续使用已登录状态。
- 用户可以查看当前会话历史消息。
- 未登录用户不能进入业务页面。

## 9. 联调方式

后端启动后，可先运行后端 MVP 联调脚本确认接口正常：

```text
cd D:/AI-Tutor/backend
powershell -ExecutionPolicy Bypass -File .\scripts\test-mvp.ps1
```

前端联调时重点验证：

- 登录后 Token 是否被保存。
- Axios 是否自动带上 Authorization 请求头。
- 学习档案保存后刷新是否仍能查询。
- AI 聊天后消息是否写入历史记录。
- 401 响应是否能引导用户重新登录。
