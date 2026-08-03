CREATE TABLE IF NOT EXISTS agent_suggestion (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  agent_type VARCHAR(32) NOT NULL,
  title VARCHAR(120) NOT NULL,
  suggestion TEXT NOT NULL,
  reason TEXT,
  action_type VARCHAR(64) NOT NULL,
  action_payload TEXT,
  impact_level VARCHAR(16) NOT NULL DEFAULT 'medium',
  requires_confirmation TINYINT NOT NULL DEFAULT 1,
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirm_time DATETIME,
  complete_time DATETIME,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_agent_suggestion_user_status (user_id, status),
  INDEX idx_agent_suggestion_user_type (user_id, agent_type),
  INDEX idx_agent_suggestion_create_time (create_time)
);

CREATE TABLE IF NOT EXISTS agent_event_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  suggestion_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  note TEXT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_agent_event_suggestion (suggestion_id),
  INDEX idx_agent_event_user (user_id)
);
