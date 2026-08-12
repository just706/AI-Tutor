SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_map_dependency (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  prerequisite_point_id BIGINT NOT NULL,
  dependent_point_id BIGINT NOT NULL,
  relation_type VARCHAR(32) NOT NULL DEFAULT 'required',
  relation_reason VARCHAR(255),
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_knowledge_map_dependency (prerequisite_point_id, dependent_point_id),
  INDEX idx_knowledge_map_dependent (dependent_point_id),
  INDEX idx_knowledge_map_prerequisite (prerequisite_point_id)
);

INSERT IGNORE INTO knowledge_map_dependency
    (prerequisite_point_id, dependent_point_id, relation_type, relation_reason, sort_order)
SELECT prerequisite.id, dependent.id, 'required', '理解变量、类型和基本语法是阅读 HashMap 使用代码的基础。', 10
FROM knowledge_point prerequisite
JOIN knowledge_point dependent ON dependent.subject = prerequisite.subject
WHERE prerequisite.subject = 'Java'
  AND prerequisite.name = '基础语法'
  AND dependent.name = 'HashMap';

INSERT IGNORE INTO knowledge_map_dependency
    (prerequisite_point_id, dependent_point_id, relation_type, relation_reason, sort_order)
SELECT prerequisite.id, dependent.id, 'required', '理解对象、引用和 equals/hashCode 有助于理解 HashMap 的键值对语义。', 20
FROM knowledge_point prerequisite
JOIN knowledge_point dependent ON dependent.subject = prerequisite.subject
WHERE prerequisite.subject = 'Java'
  AND prerequisite.name = '面向对象'
  AND dependent.name = 'HashMap';

INSERT IGNORE INTO knowledge_map_dependency
    (prerequisite_point_id, dependent_point_id, relation_type, relation_reason, sort_order)
SELECT prerequisite.id, dependent.id, 'required', '先理解集合框架中的接口与集合分类，再学习 HashMap 更容易建立整体位置。', 30
FROM knowledge_point prerequisite
JOIN knowledge_point dependent ON dependent.subject = prerequisite.subject
WHERE prerequisite.subject = 'Java'
  AND prerequisite.name = '集合框架'
  AND dependent.name = 'HashMap';
