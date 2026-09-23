SET NAMES utf8mb4;

SET @stage17_stage_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'personal_graph_extraction'
    AND column_name = 'stage'
);
SET @stage17_stage_sql = IF(
  @stage17_stage_column_exists = 0,
  'ALTER TABLE personal_graph_extraction ADD COLUMN stage VARCHAR(32) NOT NULL DEFAULT ''queued'' AFTER status',
  'SELECT 1'
);
PREPARE stage17_stage_statement FROM @stage17_stage_sql;
EXECUTE stage17_stage_statement;
DEALLOCATE PREPARE stage17_stage_statement;

SET @stage17_progress_column_exists = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'personal_graph_extraction'
    AND column_name = 'progress'
);
SET @stage17_progress_sql = IF(
  @stage17_progress_column_exists = 0,
  'ALTER TABLE personal_graph_extraction ADD COLUMN progress TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER stage',
  'SELECT 1'
);
PREPARE stage17_progress_statement FROM @stage17_progress_sql;
EXECUTE stage17_progress_statement;
DEALLOCATE PREPARE stage17_progress_statement;

UPDATE personal_graph_extraction
SET stage = CASE
  WHEN status = 'published' THEN 'published'
  WHEN status = 'completed' THEN 'awaiting_review'
  WHEN status = 'failed' THEN 'failed'
  ELSE 'queued'
END,
progress = CASE
  WHEN status IN ('completed', 'published') THEN 100
  ELSE progress
END
WHERE stage IS NULL
   OR stage = ''
   OR (stage = 'queued' AND status IN ('completed', 'published', 'failed'));
