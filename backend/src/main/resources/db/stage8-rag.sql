CREATE TABLE IF NOT EXISTS document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(32) NOT NULL,
  storage_path VARCHAR(500) NOT NULL,
  process_status VARCHAR(32) NOT NULL DEFAULT 'pending',
  upload_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_document_user_id (user_id),
  INDEX idx_document_status (process_status)
);

CREATE TABLE IF NOT EXISTS document_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  chunk_text TEXT NOT NULL,
  chunk_index INT NOT NULL,
  embedding_id VARCHAR(128),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chunk_document_id (document_id),
  INDEX idx_chunk_embedding_id (embedding_id)
);
