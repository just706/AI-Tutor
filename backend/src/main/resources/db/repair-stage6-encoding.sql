SET NAMES utf8mb4;
USE ai_tutor;

-- Repairs Java seed knowledge points that were imported by a non-UTF-8 MySQL client.
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

UPDATE knowledge_point child
JOIN knowledge_point parent ON child.parent_id = parent.id
JOIN knowledge_point root ON parent.parent_id = root.id
SET child.name = CASE child.sort_order
  WHEN 10 THEN 'HashMap'
  WHEN 20 THEN 'ArrayList'
  ELSE child.name
END
WHERE root.subject = 'Java'
  AND root.name = 'Java'
  AND root.parent_id = 0
  AND parent.subject = 'Java'
  AND parent.sort_order = 30
  AND child.subject = 'Java'
  AND child.sort_order IN (10, 20);
