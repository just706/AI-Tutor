SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_point (
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

CREATE TABLE IF NOT EXISTS learning_record (
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

INSERT IGNORE INTO knowledge_point (subject, name, parent_id, sort_order)
VALUES ('Java', 'Java', 0, 1);

-- Keep the seed tree readable if this script was imported before the UTF-8 client setting existed.
UPDATE knowledge_point child
JOIN knowledge_point root ON child.parent_id = root.id
SET child.name = CASE child.sort_order
  WHEN 10 THEN '基础语法'
  WHEN 20 THEN '面向对象'
  WHEN 30 THEN '集合框架'
  WHEN 40 THEN '异常处理'
  ELSE child.name
END
WHERE root.subject = 'Java'
  AND root.name = 'Java'
  AND root.parent_id = 0
  AND child.subject = 'Java'
  AND child.sort_order IN (10, 20, 30, 40);

INSERT IGNORE INTO knowledge_point (subject, name, parent_id, sort_order)
SELECT 'Java', '基础语法', root.id, 10
FROM knowledge_point root
WHERE root.subject = 'Java'
  AND root.name = 'Java'
  AND root.parent_id = 0;

INSERT IGNORE INTO knowledge_point (subject, name, parent_id, sort_order)
SELECT 'Java', '面向对象', root.id, 20
FROM knowledge_point root
WHERE root.subject = 'Java'
  AND root.name = 'Java'
  AND root.parent_id = 0;

INSERT IGNORE INTO knowledge_point (subject, name, parent_id, sort_order)
SELECT 'Java', '集合框架', root.id, 30
FROM knowledge_point root
WHERE root.subject = 'Java'
  AND root.name = 'Java'
  AND root.parent_id = 0;

INSERT IGNORE INTO knowledge_point (subject, name, parent_id, sort_order)
SELECT 'Java', '异常处理', root.id, 40
FROM knowledge_point root
WHERE root.subject = 'Java'
  AND root.name = 'Java'
  AND root.parent_id = 0;

INSERT IGNORE INTO knowledge_point (subject, name, parent_id, sort_order)
SELECT 'Java', 'HashMap', parent.id, 10
FROM knowledge_point parent
WHERE parent.subject = 'Java'
  AND parent.name = '集合框架';

INSERT IGNORE INTO knowledge_point (subject, name, parent_id, sort_order)
SELECT 'Java', 'ArrayList', parent.id, 20
FROM knowledge_point parent
WHERE parent.subject = 'Java'
  AND parent.name = '集合框架';
