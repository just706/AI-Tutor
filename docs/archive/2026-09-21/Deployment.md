# AI Tutor 部署文档

## 1. 部署目标

本文档用于说明 AI Tutor 系统在开发环境和生产环境中的部署方式。

系统包含：

- Vue3 前端。
- Spring Boot 后端。
- MySQL 数据库。
- Redis 缓存，可选。
- 向量数据库。
- 大模型 API 配置。
- 文件存储目录。

## 2. 环境要求

### 2.1 基础环境

| 组件 | 建议版本 |
|---|---|
| JDK | 17 或以上 |
| Maven | 3.8 或以上 |
| Node.js | 18 或以上 |
| MySQL | 8.0 或以上 |
| Redis | 6.0 或以上 |
| Docker | 可选 |

### 2.2 AI 相关环境

需要准备：

- 大模型 API Key。
- Embedding 模型配置。
- 向量数据库地址。
- RAG 文档存储目录。

## 3. 目录结构建议

```text
AI-Tutor
├── backend
├── frontend
├── docs
├── deploy
└── data
    ├── uploads
    └── logs
```

## 4. 配置说明

### 4.1 后端配置

建议在后端配置文件中维护：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ai_tutor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
  redis:
    host: localhost
    port: 6379

ai:
  deepseek:
    api-key: your_api_key
    base-url: https://api.deepseek.com
    model-name: deepseek-v4-flash
    timeout-ms: 60000
    max-context-messages: 10

rag:
  vector-store: milvus
  top-k: 5
  upload-dir: D:/AI-Tutor/data/uploads

app:
  jwt:
    secret: your-long-random-secret
    issuer: ai-tutor
    expire-minutes: 120
```

### 4.2 前端配置

前端需要配置后端 API 地址：

```text
VITE_API_BASE_URL=http://localhost:8080/api
```

## 5. 数据库部署

### 5.1 创建数据库

```sql
CREATE DATABASE ai_tutor DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 5.2 初始化表结构

使用 `Database.md` 中的建表语句初始化数据库。

如果按阶段验证后端模块，可以按顺序执行：

```text
backend/src/main/resources/db/stage1-user.sql
backend/src/main/resources/db/stage2-student-profile.sql
backend/src/main/resources/db/stage3-conversation-chat.sql
backend/src/main/resources/db/stage4-ai-chat.sql
backend/src/main/resources/db/stage12-learning-session.sql
backend/src/main/resources/db/stage13-knowledge-map.sql
backend/src/main/resources/db/stage14-learner-memory.sql
backend/src/main/resources/db/stage15-evaluation-governance.sql
```

已完成 Phase 2 的数据库需补执行 `stage13-knowledge-map.sql`。该脚本会创建 `knowledge_map_dependency` 并写入幂等的 HashMap 前置依赖种子数据。

启用 Phase 4 前，还需执行 `stage14-learner-memory.sql`：

请让 MySQL 客户端直接读取 SQL 文件，避免 PowerShell 管道按本机代码页重新编码中文内容：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --default-character-set=utf8mb4 -uroot -p ai_tutor -e "source D:/AI-Tutor/backend/src/main/resources/db/stage13-knowledge-map.sql"
```

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --default-character-set=utf8mb4 -uroot -p ai_tutor -e "source D:/AI-Tutor/backend/src/main/resources/db/stage14-learner-memory.sql"
```

启用 Phase 5 前，还需执行 `stage15-evaluation-governance.sql`：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --default-character-set=utf8mb4 -uroot -p ai_tutor -e "source D:/AI-Tutor/backend/src/main/resources/db/stage15-evaluation-governance.sql"
```

建议后期使用数据库迁移工具管理脚本，例如：

- Flyway。
- Liquibase。

## 6. 开发环境启动

### 6.1 启动 MySQL

确保 MySQL 已启动，并创建 `ai_tutor` 数据库。

### 6.2 启动 Redis

如果系统启用 Redis 缓存，需要启动 Redis 服务。

### 6.3 启动后端

进入后端目录，优先使用项目内启动脚本。脚本会读取 `backend/.env`，使用项目内 Maven 缓存 `backend/.m2/repository`，然后打包并启动 Spring Boot 服务。

建议复制 `backend/.env.example` 为 `backend/.env`，至少填写数据库密码和 DeepSeek API Key：

```text
SPRING_DATASOURCE_PASSWORD=your_mysql_password
DEEPSEEK_API_KEY=your_api_key
```

本地前端开发服务默认允许任意 `localhost` 端口，避免 Vite 在默认端口被占用时自动换端口后出现 CORS 403。生产环境应通过 `CORS_ALLOWED_ORIGINS` 配置实际前端来源，例如：

```text
CORS_ALLOWED_ORIGINS=https://tutor.example.com
```

也可以在 PowerShell 中临时设置：

```text
$env:SPRING_DATASOURCE_PASSWORD="your_mysql_password"
$env:DEEPSEEK_API_KEY="your_api_key"
```

```text
cd D:/AI-Tutor/backend
.\run.cmd
```

如果需要手动拆分执行，使用：

```text
.\mvnw.cmd package -DskipTests
java -jar target/ai-tutor-backend-0.0.1-SNAPSHOT.jar
```

本项目在 Windows 本地开发时以 `run.cmd` 作为标准启动方式。`spring-boot:run` 不是当前推荐路径，避免环境差异导致类路径或依赖缓存问题。

默认访问地址：

```text
http://localhost:8080
```

### 6.4 启动前端

进入前端目录，安装依赖并启动开发服务。

```text
npm install
npm run dev
```

默认访问地址：

```text
http://localhost:5173
```

## 7. 生产环境部署

### 7.1 前端构建

```text
npm run build
```

构建结果输出到：

```text
dist
```

### 7.2 后端打包

```text
mvn clean package
```

打包结果通常位于：

```text
target/*.jar
```

### 7.3 启动后端服务

```text
java -jar ai-tutor-backend.jar
```

建议生产环境使用进程管理工具：

- systemd。
- Docker。
- Supervisor。

## 8. Nginx 配置示例

```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        root /opt/ai-tutor/frontend/dist;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

## 9. Docker Compose 部署示例

```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    container_name: ai-tutor-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root_password
      MYSQL_DATABASE: ai_tutor
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql

  redis:
    image: redis:7
    container_name: ai-tutor-redis
    ports:
      - "6379:6379"

  backend:
    image: ai-tutor-backend:latest
    container_name: ai-tutor-backend
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/ai_tutor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: root_password
      DEEPSEEK_API_KEY: your_api_key
      DEEPSEEK_MODEL_NAME: deepseek-v4-flash
    depends_on:
      - mysql
      - redis

volumes:
  mysql_data:
```

## 10. 文件上传部署

文件上传建议独立目录存储：

```text
D:/AI-Tutor/data/uploads
```

要求：

- 目录需要后端进程可读写。
- 不建议把上传文件放在代码目录下。
- 生产环境应限制文件类型和大小。
- 重要资料应定期备份。

## 11. 日志配置

建议日志目录：

```text
D:/AI-Tutor/data/logs
```

需要记录：

- 后端业务日志。
- AI 调用日志。
- 文件处理日志。
- 异常错误日志。

生产环境建议按天滚动日志，避免日志文件过大。

## 12. 安全配置

上线前需要检查：

- 数据库密码不能使用默认密码。
- API Key 不能写死在代码中。
- 文件上传需要限制类型和大小。
- 管理员接口必须做权限控制。
- HTTPS 建议开启。
- 生产环境关闭详细错误堆栈返回。

## 13. 部署验收

部署完成后检查：

- 前端页面可以正常访问。
- 用户可以注册和登录。
- 后端接口可以正常调用。
- 数据库可以正常读写。
- AI 问答可以正常返回。
- 文件上传目录可正常写入。
- RAG 文档处理可以正常执行。
- 日志文件正常生成。

后端 MVP 阶段可以使用联调脚本验证核心闭环：

```text
cd D:/AI-Tutor/backend
powershell -ExecutionPolicy Bypass -File .\scripts\test-mvp.ps1
```

不调用 DeepSeek 的快速验证：

```text
powershell -ExecutionPolicy Bypass -File .\scripts\test-mvp.ps1 -SkipAi
```

详细说明见 `docs/MVP-Test.md`。

## 14. 常见问题

### 14.1 后端无法连接数据库

检查：

- MySQL 是否启动。
- 数据库地址和端口是否正确。
- 用户名和密码是否正确。
- 数据库是否已创建。

### 14.2 AI 接口调用失败

检查：

- API Key 是否正确。
- 模型名称是否正确。
- 网络是否可以访问模型服务。
- 请求是否超过模型限制。

### 14.3 文件上传失败

检查：

- 上传目录是否存在。
- 后端是否有写入权限。
- 文件大小是否超过限制。
- 文件类型是否被允许。
