SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS learner_memory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  memory_type VARCHAR(32) NOT NULL,
  topic VARCHAR(128),
  content VARCHAR(255) NOT NULL,
  confidence INT NOT NULL DEFAULT 60,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  source_session_id BIGINT,
  source_conversation_id BIGINT,
  last_observed_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expire_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_learner_memory_user_status (user_id, status),
  INDEX idx_learner_memory_user_expire (user_id, expire_time),
  INDEX idx_learner_memory_user_type_topic (user_id, memory_type, topic)
);
