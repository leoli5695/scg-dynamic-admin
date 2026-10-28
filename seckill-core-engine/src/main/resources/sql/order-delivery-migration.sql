-- Apply to ds_0 before enabling the new order consumer. No existing rows are modified.
CREATE TABLE IF NOT EXISTS seckill_db_0.order_delivery (
  order_no VARCHAR(64) NOT NULL PRIMARY KEY,
  payload MEDIUMTEXT NOT NULL,
  delay_level INT NOT NULL,
  es_sent TINYINT NOT NULL DEFAULT 0,
  rollback_sent TINYINT NOT NULL DEFAULT 0,
  completed TINYINT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pending_delivery (completed, next_attempt_at, order_no),
  KEY idx_delivery_cleanup (completed, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
