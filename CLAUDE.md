# AI Tutor 协作规则

## 文档入口

- 项目范围和验收：`docs/PRD.md`
- 当前架构：`docs/Architecture.md`
- AI 行为和限制：`docs/AI-Design.md`
- 当前接口：`docs/API.md`
- 数据库：`docs/Database.md`
- 开发、验证和待办：`docs/Development.md`

## 修改规则

1. 先确认变更属于当前实现、缺陷修复还是候选方案，并在对应文档中使用准确措辞。
2. 不把计划中的 Python、LangChain/LangGraph、向量数据库、SSE 或 Multi-Agent 写成已上线能力。
3. 不提交 `.env`、API Key、数据库密码、上传资料、日志、构建产物、依赖缓存或崩溃转储。
4. 文档描述接口、字段、表或脚本时，以当前 Java 控制器、DTO/VO 和 SQL 为准；计划接口必须明确标为未实现。
5. 用户数据必须按当前用户校验所有权。模型或 Agent 不得直接执行 SQL 或绕过 Java 的权限与事务边界。
6. 新增外部服务前先说明故障、超时、回退和数据一致性处理；不要同时维护两套 Prompt、会话历史或写入逻辑。

## 验证

- Java 改动运行相关单元测试或 `mvnw verify`。
- 前端改动运行 `npm run build`。
- 接口改动同步检查前端调用和 `docs/API.md`。
- 数据库改动同步检查脚本顺序、重复执行行为和 [docs/Database.md](docs/Database.md)。
- 文档整理检查链接、端点清单、表清单和实现边界；无需为纯文档改动修改业务代码。

历史材料在 `docs/archive/2026-09-21/`，除非明确进行历史追溯，不要将其当作现行规范。
