CREATE TABLE IF NOT EXISTS question (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_point_id BIGINT NOT NULL,
  question_type VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  options TEXT,
  answer TEXT NOT NULL,
  analysis TEXT,
  difficulty VARCHAR(32) NOT NULL DEFAULT 'medium',
  source VARCHAR(32) NOT NULL DEFAULT 'ai',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_question_knowledge (knowledge_point_id),
  INDEX idx_question_difficulty (difficulty),
  INDEX idx_question_type (question_type)
);

CREATE TABLE IF NOT EXISTS answer_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  user_answer TEXT,
  is_correct TINYINT,
  score INT,
  ai_feedback TEXT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_answer_user_id (user_id),
  INDEX idx_answer_question_id (question_id)
);
