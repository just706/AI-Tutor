-- 保存回答时的有序教材片段。旧消息保持 NULL，不推断或回填引用。
SET @stage19_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_history' AND column_name = 'rag_sources'
);
SET @stage19_sql = IF(@stage19_exists = 0,
  'ALTER TABLE chat_history ADD COLUMN rag_sources JSON NULL AFTER message_content', 'SELECT 1');
PREPARE stage19_statement FROM @stage19_sql;
EXECUTE stage19_statement;
DEALLOCATE PREPARE stage19_statement;
