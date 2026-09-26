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
| 整体开发路线、当前进度、阶段验收与本地开发 | [docs/Development.md](docs/Development.md) |

整体规划统一维护在 [Development 的总体路线](docs/Development.md#总体路线与当前进度)。阶段 0 已验收，阶段 1 正在进行：教材问答已统一入口、保存会话教材和回答引用，并通过问句结构支持跨学科的简单连续追问；刷新后可继续提问和查看原文依据。下一项是失败重试，随后完成固定题集，再推进教学闭环、学习证据和效果验证。Java 入门教材是教学闭环的首批试点；教材问答不依赖 Java 概念名单，已加入数学、物理等回归。Python / LangGraph 等架构方案按评测结果决定是否采用。

历史方案和阶段记录放在 `docs/archive/2026-09-21/`，只用于追溯背景，不作为当前实现或验收依据。

## 当前实现边界

- 后端：Java 17、Spring Boot 3.3.5、MyBatis-Plus、MySQL。
- 前端：Vue 3、TypeScript、Pinia、Vue Router、Element Plus、Vite。
- 模型：通过 DeepSeek HTTP API 同步生成回答；未接入 Python、LangChain/LangGraph、向量数据库或 Multi-Agent 运行时。
- RAG：支持 TXT/Markdown/PDF/DOC/DOCX 解析、分片和用户文档检索；当前是 MySQL 关键词基线，不是 embedding 向量检索。
- 个人图谱：文档上传后可异步提取候选节点和关系，用户发布后才替换自己的个人图谱。

“已实现”和“计划采用”必须分开描述。候选的 Java + Python 方案见 [docs/Development.md](docs/Development.md)，目前不改变现有运行方式。

## 配置

首次配置时，将 `backend/.env.example` 复制为 `backend/.env`，填写数据库和模型配置；已有配置时继续使用，不要覆盖。不要把 `.env`、上传文件、日志、构建目录或密钥打进压缩包。`backend/run.cmd` 会读取同目录的 `.env`，但直接执行 `mvnw.cmd` 或 `java -jar` 不会自动读取它，此时请先在当前终端设置环境变量。

主要默认值：后端 `8080`，数据库 `localhost:3306/ai_tutor`，JWT 有效期 120 分钟，文档大小 5 MB，分片 800 字符、重叠 120、检索前 4 个结果。生产部署必须显式设置 `JWT_SECRET`、数据库密码和 `DEEPSEEK_API_KEY`。

## 启动（Windows PowerShell）

准备 Java 17、Node.js（Vite 8 要求 Node `^20.19.0` 或 `>=22.12.0`），并启动 MySQL。首次建库按 [docs/Database.md](docs/Database.md) 执行脚本；已有数据库在启动新后端前，按其中的升级说明执行 `stage18-conversation-documents.sql` 和 `stage19-chat-rag-sources.sql`，两者均可重复执行。旧教材会话需要重新选择一次教材；旧回答没有保存过引用时，不会自动补出引用。

以下命令以项目位于 `D:\AI-Tutor` 为例；项目放在其他位置时，替换对应路径。只复制代码块中的命令，不要复制终端里的 `PS D:\AI-Tutor>` 或 `>>` 提示符。

先在一个 PowerShell 窗口启动后端：

```powershell
cd D:\AI-Tutor\backend
.\run.cmd
```

`cd` 用于切换目录，执行后提示符应显示 `PS D:\AI-Tutor\backend>`。`run.cmd` 会先构建，再启动后端；日志出现 `Started AiTutorApplication` 后，保持这个窗口打开。后端默认使用 `8080` 端口。

再打开另一个 PowerShell 窗口启动前端：

```powershell
cd D:\AI-Tutor\frontend
npm.cmd install
npm.cmd run dev
```

依赖已经安装且没有变化时，可以跳过 `npm.cmd install`。前端默认地址是 `http://localhost:5173`；以终端实际显示的 `Local` 地址为准。`/api` 请求代理到 `http://localhost:8080`。保持两个窗口运行，在浏览器打开前端地址即可使用；需要停止时，在对应窗口按 `Ctrl+C`。

### 找不到启动命令时

- 如果提示符仍是 `PS D:\AI-Tutor>`，先执行 `cd D:\AI-Tutor\backend`，再执行 `.\run.cmd`；项目根目录没有 `run.cmd`。
- 单独输入 `D:\AI-Tutor\backend` 不会切换目录，路径前必须加 `cd`。
- `copilot D:\AI-Tutor\backend` 会调用 Copilot 命令，不能切换目录或启动本项目；启动 AI Tutor 不需要安装 Copilot CLI。

## 检查

- 后端：`cd backend; .\mvnw.cmd -o verify`（离线缓存可用时）。
- 前端：在 `frontend` 目录运行 `npm.cmd test` 和 `npm.cmd run build`。
- API 返回统一的 `{code,message,data}`；业务错误通常仍以 HTTP 200 返回，具体以 `code` 和接口文档为准。
- `backend/scripts/test-mvp.ps1` 是旧的 AI 聊天冒烟脚本，不等同于完整学习闭环验收；个人图谱脚本也需要等待异步处理完成。
- `backend/scripts/verify-stage0.mjs` 使用独立临时数据库和本地模型替身验证基本接口流程；运行方法和验收范围见 [Development](docs/Development.md#本地开发与验证)。

## SQL 说明

数据库目前采用按阶段命名的初始化/演进脚本，没有 Flyway 或 Liquibase 版本表。新库按 [docs/Database.md](docs/Database.md) 的顺序执行；已存在的库先检查列、索引和字符集，不要盲目重复执行带有数据更新的脚本。

## 已知限制

关键词 RAG 对中文改写和跨片段问题有限；连续追问采用有限规则，无法确定指代时需要明确对象名称；开放题评分依赖模型；学习掌握度仍是 0–100 的工程指标，不能当作心理测量结果；学习计划和多数 Agent 建议是规则生成；主教师请求是同步链路。下一步按开发路线完善失败重试，用固定题集验证检索与回答，再验证教学闭环。
