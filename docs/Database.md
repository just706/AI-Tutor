# AI Tutor 数据库设计

## 1. 设计说明

数据库主要使用 MySQL 存储结构化业务数据，包括用户、学习档案、聊天记录、知识点、题目、答题记录、文档信息和学习计划等。

向量数据不建议直接存储在 MySQL 中，建议使用 Milvus、pgvector、Elasticsearch Vector、Qdrant 等向量数据库。MySQL 中只保存文档切片和向量 ID 的关联关系。

## 2. 表结构总览

| 表名 | 说明 |
|---|---|
| user | 用户信息 |
| student_profile | 学习档案 |
| conversation | 会话信息 |
| chat_history | 聊天记录 |
| knowledge_point | 知识点 |
| learning_record | 学习记录 |
| question | 题目 |
| answer_record | 答题记录 |
| document | 上传文档 |
| document_chunk | 文档切片 |
| study_plan | 学习计划 |
| ai_call_log | AI 调用日志 |

## 3. 用户表 user

### 3.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| username | VARCHAR(64) | 用户名 |
| password_hash | VARCHAR(255) | 加密后的密码 |
| nickname | VARCHAR(64) | 昵称 |
| avatar_url | VARCHAR(255) | 头像地址 |
| role | VARCHAR(32) | 角色，student/admin |
| status | TINYINT | 状态，1 正常，0 禁用 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 3.2 建表语句

```sql
CREATE TABLE user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(64),
  avatar_url VARCHAR(255),
  role VARCHAR(32) NOT NULL DEFAULT 'student',
  status TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

## 4. 学习档案表 student_profile

### 4.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| learning_direction | VARCHAR(128) | 学习方向 |
| learning_goal | VARCHAR(255) | 学习目标 |
| current_level | VARCHAR(64) | 当前水平 |
| learning_preference | VARCHAR(255) | 学习偏好 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 4.2 建表语句

```sql
CREATE TABLE student_profile (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL UNIQUE,
  learning_direction VARCHAR(128),
  learning_goal VARCHAR(255),
  current_level VARCHAR(64),
  learning_preference VARCHAR(255),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_student_profile_user_id (user_id)
);
```

## 5. 会话表 conversation

### 5.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| title | VARCHAR(128) | 会话标题 |
| mode | VARCHAR(32) | 会话模式，chat/teaching/rag |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 5.2 建表语句

```sql
CREATE TABLE conversation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(128),
  mode VARCHAR(32) NOT NULL DEFAULT 'chat',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_conversation_user_id (user_id)
);
```

## 6. 聊天记录表 chat_history

### 6.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| conversation_id | BIGINT | 会话 ID |
| role | VARCHAR(32) | 消息角色，user/assistant/system |
| message_content | TEXT | 消息内容 |
| create_time | DATETIME | 创建时间 |

### 6.2 建表语句

```sql
CREATE TABLE chat_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  message_content TEXT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chat_user_conversation (user_id, conversation_id),
  INDEX idx_chat_create_time (create_time)
);
```

## 7. 知识点表 knowledge_point

### 7.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| subject | VARCHAR(64) | 学科 |
| name | VARCHAR(128) | 知识点名称 |
| parent_id | BIGINT | 父级知识点 ID |
| sort_order | INT | 排序 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 7.2 建表语句

```sql
CREATE TABLE knowledge_point (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  subject VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  parent_id BIGINT NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_knowledge_subject_parent_name (subject, parent_id, name),
  INDEX idx_knowledge_subject (subject),
  INDEX idx_knowledge_parent_id (parent_id)
);
```

## 8. 学习记录表 learning_record

### 8.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| knowledge_point_id | BIGINT | 知识点 ID |
| learning_status | VARCHAR(32) | 学习状态 |
| mastery_level | INT | 掌握程度，0-100 |
| study_time | INT | 学习时长，分钟 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 8.2 建表语句

```sql
CREATE TABLE learning_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  learning_status VARCHAR(32) NOT NULL DEFAULT 'not_started',
  mastery_level INT NOT NULL DEFAULT 0,
  study_time INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_learning_user_knowledge (user_id, knowledge_point_id),
  INDEX idx_learning_user_id (user_id),
  INDEX idx_learning_knowledge_id (knowledge_point_id)
);
```

## 9. 题目表 question

### 9.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| knowledge_point_id | BIGINT | 知识点 ID |
| question_type | VARCHAR(32) | 题目类型 |
| content | TEXT | 题目内容 |
| options | TEXT | 选项，JSON 字符串 |
| answer | TEXT | 正确答案 |
| analysis | TEXT | 答案解析 |
| difficulty | VARCHAR(32) | 难度 |
| source | VARCHAR(32) | 来源，ai/manual |
| create_time | DATETIME | 创建时间 |

### 9.2 建表语句

```sql
CREATE TABLE question (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_point_id BIGINT NOT NULL,
  question_type VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  options TEXT,
  answer TEXT NOT NULL,
  analysis TEXT,
  difficulty VARCHAR(32) NOT NULL DEFAULT 'medium',
  source VARCHAR(32) NOT NULL DEFAULT 'ai',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_question_knowledge (knowledge_point_id),
  INDEX idx_question_difficulty (difficulty)
);
```

## 10. 答题记录表 answer_record

### 10.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| question_id | BIGINT | 题目 ID |
| user_answer | TEXT | 用户答案 |
| is_correct | TINYINT | 是否正确 |
| score | INT | 得分 |
| ai_feedback | TEXT | AI 反馈 |
| create_time | DATETIME | 创建时间 |

### 10.2 建表语句

```sql
CREATE TABLE answer_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  user_answer TEXT,
  is_correct TINYINT,
  score INT,
  ai_feedback TEXT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_answer_user_id (user_id),
  INDEX idx_answer_question_id (question_id)
);
```

## 11. 文档表 document

### 11.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 上传用户 ID |
| file_name | VARCHAR(255) | 文件名 |
| file_type | VARCHAR(32) | 文件类型 |
| storage_path | VARCHAR(500) | 文件存储路径 |
| process_status | VARCHAR(32) | 处理状态 |
| upload_time | DATETIME | 上传时间 |

### 11.2 建表语句

```sql
CREATE TABLE document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(32) NOT NULL,
  storage_path VARCHAR(500) NOT NULL,
  process_status VARCHAR(32) NOT NULL DEFAULT 'pending',
  upload_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_document_user_id (user_id),
  INDEX idx_document_status (process_status)
);
```

## 12. 文档切片表 document_chunk

### 12.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| document_id | BIGINT | 文档 ID |
| chunk_text | TEXT | 文本切片内容 |
| chunk_index | INT | 切片序号 |
| embedding_id | VARCHAR(128) | 向量数据库中的向量 ID |
| create_time | DATETIME | 创建时间 |

### 12.2 建表语句

```sql
CREATE TABLE document_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  chunk_text TEXT NOT NULL,
  chunk_index INT NOT NULL,
  embedding_id VARCHAR(128),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chunk_document_id (document_id),
  INDEX idx_chunk_embedding_id (embedding_id)
);
```

## 13. 学习计划表 study_plan

### 13.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| title | VARCHAR(128) | 计划标题 |
| plan_content | TEXT | 计划内容 |
| start_date | DATE | 开始日期 |
| end_date | DATE | 结束日期 |
| status | VARCHAR(32) | 状态 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 13.2 建表语句

```sql
CREATE TABLE study_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  plan_content TEXT NOT NULL,
  start_date DATE,
  end_date DATE,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_study_plan_user_id (user_id),
  INDEX idx_study_plan_status (status)
);
```

## 14. AI 调用日志表 ai_call_log

### 14.1 字段设计

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| user_id | BIGINT | 用户 ID |
| provider | VARCHAR(64) | 模型供应商 |
| model_name | VARCHAR(128) | 模型名称 |
| request_type | VARCHAR(64) | 请求类型 |
| prompt_tokens | INT | 输入 Token 数 |
| completion_tokens | INT | 输出 Token 数 |
| status | VARCHAR(32) | 调用状态 |
| error_message | TEXT | 错误信息 |
| create_time | DATETIME | 创建时间 |

### 14.2 建表语句

```sql
CREATE TABLE ai_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  provider VARCHAR(64),
  model_name VARCHAR(128),
  request_type VARCHAR(64),
  prompt_tokens INT DEFAULT 0,
  completion_tokens INT DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  error_message TEXT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ai_log_user_id (user_id),
  INDEX idx_ai_log_create_time (create_time)
);
```

## 15. 表关系说明

- `user` 与 `student_profile` 是一对一关系。
- `user` 与 `conversation` 是一对多关系。
- `conversation` 与 `chat_history` 是一对多关系。
- `knowledge_point` 支持父子层级结构。
- `knowledge_point` 与 `question` 是一对多关系。
- `user` 与 `learning_record` 是一对多关系。
- `user` 与 `answer_record` 是一对多关系。
- `document` 与 `document_chunk` 是一对多关系。
- `user` 与 `study_plan` 是一对多关系。

## 16. 字段枚举建议

### 16.1 用户角色 role

| 值 | 说明 |
|---|---|
| student | 学生 |
| admin | 管理员 |

### 16.2 学习状态 learning_status

| 值 | 说明 |
|---|---|
| not_started | 未开始 |
| learning | 学习中 |
| completed | 已完成 |
| need_review | 需要复习 |

### 16.3 会话模式 mode

| 值 | 说明 |
|---|---|
| chat | 普通聊天 |
| teaching | 教学模式 |
| rag | 知识库问答 |

### 16.4 文档处理状态 process_status

| 值 | 说明 |
|---|---|
| pending | 待处理 |
| processing | 处理中 |
| completed | 已完成 |
| failed | 处理失败 |
