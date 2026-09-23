SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS personal_graph_extraction (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  candidate_json JSON,
  error_message TEXT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  publish_time DATETIME,
  INDEX idx_personal_extraction_user_document (user_id, document_id, id),
  INDEX idx_personal_extraction_user_status (user_id, status)
);

CREATE TABLE IF NOT EXISTS personal_knowledge_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_key VARCHAR(255) NOT NULL,
  description TEXT,
  source JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  confidence INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_personal_node_user_document_name (user_id, document_id, name_key),
  INDEX idx_personal_node_user_document (user_id, document_id)
);

CREATE TABLE IF NOT EXISTS personal_knowledge_edge (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  source_node_id BIGINT NOT NULL,
  target_node_id BIGINT NOT NULL,
  relation_type VARCHAR(32) NOT NULL,
  relation_reason VARCHAR(255),
  source JSON NOT NULL,
  confidence INT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_personal_edge_user_nodes_type (user_id, source_node_id, target_node_id, relation_type),
  INDEX idx_personal_edge_user_document (user_id, document_id),
  INDEX idx_personal_edge_user_source (user_id, source_node_id),
  INDEX idx_personal_edge_user_target (user_id, target_node_id)
);
