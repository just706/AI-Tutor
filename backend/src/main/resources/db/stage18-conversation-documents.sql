-- 会话教材绑定；NULL 和 [] 都表示尚未选择教材，不回填全部资料。
SET @stage18_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'conversation' AND column_name = 'document_ids'
);
SET @stage18_sql = IF(@stage18_exists = 0,
  'ALTER TABLE conversation ADD COLUMN document_ids JSON NULL AFTER mode', 'SELECT 1');
PREPARE stage18_statement FROM @stage18_sql;
EXECUTE stage18_statement;
DEALLOCATE PREPARE stage18_statement;
