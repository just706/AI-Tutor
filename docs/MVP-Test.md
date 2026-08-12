# AI Tutor MVP 联调测试

## 1. 测试目标

本文档用于说明后端 MVP 闭环的本地联调方式。

测试闭环：

```text
健康检查 → 注册 → 重复注册校验 → 登录 → Token 鉴权 → 学习档案 → 会话 → 用户隔离 → AI 聊天 → 聊天记录
```

## 2. 前置条件

确保已经完成：

- MySQL 已启动。
- `ai_tutor` 数据库已创建。
- 阶段 1 到阶段 4 的 SQL 已执行。
- 后端服务正在运行。
- 后端启动前已设置 `SPRING_DATASOURCE_PASSWORD`。
- 如需测试真实 AI 聊天，后端启动前已设置 `DEEPSEEK_API_KEY`。

阶段 SQL：

```text
backend/src/main/resources/db/stage1-user.sql
backend/src/main/resources/db/stage2-student-profile.sql
backend/src/main/resources/db/stage3-conversation-chat.sql
backend/src/main/resources/db/stage4-ai-chat.sql
```

## 3. 启动后端

推荐把本机环境变量写入 `backend/.env`，不要把真实密码或 API Key 写入可提交文档：

```text
SPRING_DATASOURCE_PASSWORD=your_mysql_password
DEEPSEEK_API_KEY=your_deepseek_api_key
```

如果只在当前 PowerShell 窗口临时设置：

```powershell
cd D:\AI-Tutor\backend
$env:SPRING_DATASOURCE_PASSWORD="your_mysql_password"
$env:DEEPSEEK_API_KEY="your_deepseek_api_key"
.\run.cmd
```

`run.cmd` 是 Windows 本地标准启动方式，会读取 `backend/.env`，并使用项目内 Maven 缓存 `backend/.m2/repository`。不建议用 `spring-boot:run` 作为首选启动路径。

## 4. 启动前端

新开一个 PowerShell 窗口执行：

```powershell
cd D:\AI-Tutor\frontend
npm install
npm run dev
```

默认访问地址：

```text
http://localhost:5173
```

## 5. 运行完整 MVP 联调

新开一个 PowerShell 窗口执行：

```powershell
cd D:\AI-Tutor\backend
powershell -ExecutionPolicy Bypass -File .\scripts\test-mvp.ps1
```

如果只想验证后端核心接口，不调用 DeepSeek：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-mvp.ps1 -SkipAi
```

如果需要指定后端地址：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-mvp.ps1 -BaseUrl "http://localhost:8080"
```

如果需要减少 AI 调用消耗：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-mvp.ps1 -AiMessage "Reply with OK only."
```

## 6. 通过标准

脚本最后输出：

```text
[MVP] MVP integration test passed
```

表示核心闭环通过。

## 7. 覆盖场景

脚本会验证：

- 健康检查返回成功。
- 用户可以注册。
- 重复用户名会被拒绝。
- 错误密码无法登录。
- 正确账号可以登录并获得 Token。
- 未携带 Token 和无效 Token 无法访问受保护接口。
- 当前用户可以保存并读取学习档案。
- 当前用户可以创建并查询会话。
- 第二个用户无法读取第一个用户的会话消息。
- 完整模式下，AI 聊天会返回回答。
- 完整模式下，聊天记录包含用户消息和 AI 回复。

## 7. 注意事项

- 脚本会创建临时测试用户和测试会话，不会自动删除数据。
- `-SkipAi` 模式不会调用 DeepSeek，也不会验证 AI 回复保存。
- 如果完整模式返回 `code: 600`，优先检查后端进程是否已配置 `DEEPSEEK_API_KEY`。
- 如果 PowerShell 阻止脚本运行，使用文档中的 `-ExecutionPolicy Bypass` 命令即可，不需要修改系统策略。
