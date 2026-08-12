-- Phase 5: persist AI call latency for evaluation and engineering governance.
-- The checks keep this migration safe to rerun on the same MySQL schema.

SET @stage15_duration_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_call_log'
    AND COLUMN_NAME = 'duration_ms'
);
SET @stage15_duration_sql = IF(
  @stage15_duration_column_exists = 0,
  'ALTER TABLE ai_call_log ADD COLUMN duration_ms INT NOT NULL DEFAULT 0 AFTER request_type',
  'DO 0'
);
PREPARE stage15_duration_statement FROM @stage15_duration_sql;
EXECUTE stage15_duration_statement;
DEALLOCATE PREPARE stage15_duration_statement;

SET @stage15_latency_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_call_log'
    AND INDEX_NAME = 'idx_ai_log_user_status_time'
);
SET @stage15_latency_index_sql = IF(
  @stage15_latency_index_exists = 0,
  'CREATE INDEX idx_ai_log_user_status_time ON ai_call_log (user_id, status, create_time)',
  'DO 0'
);
PREPARE stage15_latency_index_statement FROM @stage15_latency_index_sql;
EXECUTE stage15_latency_index_statement;
DEALLOCATE PREPARE stage15_latency_index_statement;
