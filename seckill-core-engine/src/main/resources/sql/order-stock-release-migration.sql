-- Run on the MySQL server hosting the 8 seckill databases, BEFORE deploying this version.
-- Existing terminal orders remain release_state=0: never guess whether old stock was already returned.
DROP PROCEDURE IF EXISTS seckill_db_0.add_order_release_state;
DELIMITER $$
CREATE PROCEDURE seckill_db_0.add_order_release_state()
BEGIN
  DECLARE finished INT DEFAULT 0;
  DECLARE db_name VARCHAR(64);
  DECLARE table_name_value VARCHAR(64);
  DECLARE targets CURSOR FOR
    SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.TABLES
    WHERE TABLE_SCHEMA REGEXP '^seckill_db_[0-7]$' AND TABLE_NAME REGEXP '^order_[0-9]+$';
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=1;
  OPEN targets;
  migration_loop: LOOP
    FETCH targets INTO db_name,table_name_value;
    IF finished=1 THEN LEAVE migration_loop; END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=db_name AND TABLE_NAME=table_name_value AND COLUMN_NAME='stock_release_state') THEN
      SET @release_ddl=CONCAT('ALTER TABLE `',db_name,'`.`',table_name_value,'` ADD COLUMN stock_release_state TINYINT NOT NULL DEFAULT 0, ADD COLUMN stock_release_id VARCHAR(64) NULL, ADD COLUMN stock_release_next_at DATETIME NULL, ADD INDEX idx_stock_release (stock_release_state, stock_release_next_at, id)');
      PREPARE release_stmt FROM @release_ddl; EXECUTE release_stmt; DEALLOCATE PREPARE release_stmt;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=db_name AND TABLE_NAME=table_name_value AND COLUMN_NAME='stock_release_id')
      OR NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=db_name AND TABLE_NAME=table_name_value AND COLUMN_NAME='stock_release_next_at')
      OR NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=db_name AND TABLE_NAME=table_name_value AND INDEX_NAME='idx_stock_release') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Incomplete order release schema: repair before deploying';
    END IF;
  END LOOP;
  CLOSE targets;
END$$
DELIMITER ;
CALL seckill_db_0.add_order_release_state();
DROP PROCEDURE seckill_db_0.add_order_release_state;
-- If sharding is disabled, apply the same three columns and index to the local seckill_order table.
