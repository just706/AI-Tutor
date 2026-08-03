CREATE TABLE IF NOT EXISTS ai_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  provider VARCHAR(64),
  model_name VARCHAR(128),
  request_type VARCHAR(64),
  prompt_tokens INT DEFAULT 0,
  completion_tokens INT DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  error_message TEXT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ai_log_user_id (user_id),
  INDEX idx_ai_log_create_time (create_time)
);
