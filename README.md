# AI Tutor

面向计算机初学者的个性化 AI 学习助手。当前版本把 Java/Vue 学习系统、对话记录、知识点、练习、学习分析、个人资料库和个人知识图谱放在一个可运行的项目中。

## 先看什么

| 想了解 | 文档 |
| --- | --- |
| 产品范围、当前验收口径 | [docs/PRD.md](docs/PRD.md) |
| 当前代码如何协作 | [docs/Architecture.md](docs/Architecture.md) |
| AI、记忆、教学策略和 RAG | [docs/AI-Design.md](docs/AI-Design.md) |
| 现有 HTTP 接口 | [docs/API.md](docs/API.md) |
| 表、索引和脚本顺序 | [docs/Database.md](docs/Database.md) |
| 本地开发和下一步计划 | [docs/Development.md](docs/Development.md) |

历史方案和阶段记录放在 `docs/archive/2026-09-21/`，只用于追溯背景，不作为当前实现或验收依据。

## 当前实现边界

- 后端：Java 17、Spring Boot 3.3.5、MyBatis-Plus、MySQL。
- 前端：Vue 3、TypeScript、Pinia、Vue Router、Element Plus、Vite。
- 模型：通过 DeepSeek HTTP API 同步生成回答；未接入 Python、LangChain/LangGraph、向量数据库或 Multi-Agent 运行时。
- RAG：支持 TXT/Markdown/PDF/DOC/DOCX 解析、分片和用户文档检索；当前是 MySQL 关键词基线，不是 embedding 向量检索。
- 个人图谱：文档上传后可异步提取候选节点和关系，用户发布后才替换自己的个人图谱。

“已实现”和“计划采用”必须分开描述。候选的 Java + Python 方案见 [docs/Development.md](docs/Development.md)，目前不改变现有运行方式。

## 配置

复制 `.env.example` 为 `.env`，填写数据库和模型配置；不要把 `.env`、上传文件、日志、构建目录或密钥打进压缩包。`run.cmd` 会读取 `.env`，但直接执行 `mvnw.cmd` 或 `java -jar` 不会自动读取它，此时请先在当前终端设置环境变量。

主要默认值：后端 `8080`，数据库 `localhost:3306/ai_tutor`，JWT 有效期 120 分钟，文档大小 5 MB，分片 800 字符、重叠 120、检索前 4 个结果。生产部署必须显式设置 `JWT_SECRET`、数据库密码和 `DEEPSEEK_API_KEY`。

## 启动

1. 准备 Java 17、Node.js（Vite 8 要求 Node `^20.19.0` 或 `>=22.12.0`）和 MySQL。
2. 先按 [docs/Database.md](docs/Database.md) 的顺序执行数据库脚本。
3. 在 `backend` 下运行 `run.cmd`，或先设置环境变量再执行 Maven 打包后的 jar。
4. 在 `frontend` 下运行 `npm install` 和 `npm run dev`。开发服务器默认使用 `5173`，`/api` 代理到 `http://localhost:8080`。
5. 打开 `http://localhost:5173`，注册用户后开始学习。

## 检查

- 后端：`cd backend; .\mvnw.cmd -o verify`（离线缓存可用时）。
- 前端：`cd frontend; npm run build`。
- API 返回统一的 `{code,message,data}`；业务错误通常仍以 HTTP 200 返回，具体以 `code` 和接口文档为准。
- `backend/scripts/test-mvp.ps1` 是旧的 AI 聊天冒烟脚本，不等同于完整学习闭环验收；个人图谱脚本也需要等待异步处理完成。

## SQL 说明

数据库目前采用按阶段命名的初始化/演进脚本，没有 Flyway 或 Liquibase 版本表。新库按 [docs/Database.md](docs/Database.md) 的顺序执行；已存在的库先检查列、索引和字符集，不要盲目重复执行带有数据更新的脚本。

## 已知限制

关键词 RAG 对中文改写和跨片段问题有限；开放题评分依赖模型；学习掌握度仍是 0–100 的工程指标，不能当作心理测量结果；学习计划和多数 Agent 建议是规则生成；主教师请求是同步链路。下一步优先做安全清理、RAG 评测和可解释的教学闭环，而不是直接扩展 Multi-Agent。
