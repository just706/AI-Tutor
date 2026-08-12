CREATE TABLE IF NOT EXISTS learning_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  goal VARCHAR(255) NOT NULL,
  topic VARCHAR(128),
  intent VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  current_step_type VARCHAR(64),
  teaching_strategy VARCHAR(64),
  next_action VARCHAR(255),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  complete_time DATETIME,
  INDEX idx_learning_session_user_status (user_id, status),
  INDEX idx_learning_session_conversation_status (conversation_id, status),
  INDEX idx_learning_session_update_time (update_time)
);

CREATE TABLE IF NOT EXISTS learning_session_step (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  step_type VARCHAR(64) NOT NULL,
  status_from VARCHAR(32),
  status_to VARCHAR(32) NOT NULL,
  intent VARCHAR(64),
  teaching_strategy VARCHAR(64),
  user_message TEXT,
  agent_response TEXT,
  strategy_source TEXT,
  actions_snapshot TEXT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_learning_step_session_id (session_id),
  INDEX idx_learning_step_user_time (user_id, create_time)
);
