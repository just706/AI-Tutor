-- 旧会话保持 NULL，不从可修改或截断的标题推断知识点。
SET @stage24_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'conversation' AND column_name = 'teaching_knowledge_point_id'
);
SET @stage24_sql = IF(@stage24_exists = 0,
  'ALTER TABLE conversation ADD COLUMN teaching_knowledge_point_id BIGINT NULL AFTER mode', 'SELECT 1');
PREPARE stage24_statement FROM @stage24_sql;
EXECUTE stage24_statement;
DEALLOCATE PREPARE stage24_statement;
