-- 仅增加可空字段；旧会话继续普通问答，不回填学习结论。
SET @stage21_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'learning_session' AND column_name = 'flow_state'
);
SET @stage21_sql = IF(@stage21_exists = 0,
  'ALTER TABLE learning_session ADD COLUMN flow_state JSON NULL', 'SELECT 1');
PREPARE stage21_statement FROM @stage21_sql;
EXECUTE stage21_statement;
DEALLOCATE PREPARE stage21_statement;
