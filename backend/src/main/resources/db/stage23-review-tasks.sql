SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS review_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  source_evidence_id BIGINT NOT NULL,
  result_evidence_id BIGINT NULL,
  assessment_type VARCHAR(16) NOT NULL,
  snapshot JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  due_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_time DATETIME NULL,
  UNIQUE KEY uk_review_source (user_id, source_evidence_id),
  INDEX idx_review_user_status (user_id, status, due_time)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
