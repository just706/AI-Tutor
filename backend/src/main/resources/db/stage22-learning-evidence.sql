SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS learning_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_key CHAR(64) NOT NULL,
  assessment_type VARCHAR(16) NOT NULL,
  snapshot JSON NOT NULL,
  student_answer TEXT NOT NULL,
  feedback MEDIUMTEXT,
  score INT NULL,
  mastery_before INT NOT NULL,
  prior_evidence_id BIGINT NULL,
  mastery_after INT NOT NULL,
  status_after VARCHAR(32) NOT NULL,
  rule_version VARCHAR(32) NOT NULL,
  reason TEXT NOT NULL,
  response_json JSON NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_evidence_user_source (user_id, source_key),
  INDEX idx_evidence_user_knowledge (user_id, knowledge_point_id, id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
