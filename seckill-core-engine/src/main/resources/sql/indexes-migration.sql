-- ============================================================================
-- Seckill Core Engine - Index Optimization Migration
-- ============================================================================
-- PERFORMANCE FIX (P1): Add composite indexes for common query patterns
--
-- Recommendations from code review:
-- - idx_status_createtime: For processing timeout transactions by creation time
-- - idx_status_expiretime: Already exists as idx_status_expire, rename for clarity
--
-- Note: Run this script on existing databases to optimize query performance
-- ============================================================================

-- ============================================================================
-- 1. Add composite index for status + create_time
-- ============================================================================
-- Purpose: Optimize queries for processing transactions by creation time
-- Common query: SELECT * FROM transaction_log WHERE status = 0 AND create_time < timeout
-- Performance: Reduces query time from full scan to index range scan

CREATE INDEX IF NOT EXISTS idx_status_createtime 
ON seckill_db_0.transaction_log (status, create_time);

-- ============================================================================
-- 2. Add composite index for timeout queries (enhanced)
-- ============================================================================
-- Purpose: Optimize CompensationService timeout transaction queries
-- Current index idx_status_expire covers this, but add create_time for better sorting

CREATE INDEX IF NOT EXISTS idx_status_expire_created 
ON seckill_db_0.transaction_log (status, expire_time, create_time);

-- ============================================================================
-- 3. Optimize order table indexes (each db has 16 tables)
-- ============================================================================
-- Purpose: Optimize unpaid order queries
-- Common query: SELECT * FROM order_X WHERE status = 0 AND create_time < timeout

-- Note: This needs to be run on each database and each order table
-- Use the stored procedure below to batch create

DELIMITER //

CREATE PROCEDURE create_order_timeout_indexes()
BEGIN
    DECLARE db_idx INT DEFAULT 0;
    DECLARE table_idx INT DEFAULT 0;
    DECLARE db_name VARCHAR(20);
    DECLARE table_name VARCHAR(20);
    DECLARE sql_text TEXT;
    
    WHILE db_idx < 8 DO
        SET db_name = CONCAT('seckill_db_', db_idx);
        
        SET table_idx = 0;
        WHILE table_idx < 16 DO
            SET table_name = CONCAT('order_', table_idx);
            
            -- Add composite index for unpaid order timeout queries
            SET sql_text = CONCAT('CREATE INDEX IF NOT EXISTS idx_status_created ON ', 
                db_name, '.', table_name, ' (status, create_time)');
            
            EXECUTE sql_text;
            SET table_idx = table_idx + 1;
        END WHILE;
        
        SET db_idx = db_idx + 1;
    END WHILE;
END //

DELIMITER ;

-- Execute index creation
CALL create_order_timeout_indexes();

-- Cleanup
DROP PROCEDURE IF EXISTS create_order_timeout_indexes;

-- ============================================================================
-- 4. Verification queries
-- ============================================================================
-- Check indexes on transaction_log
SHOW INDEX FROM seckill_db_0.transaction_log;

-- Check indexes on order tables (sample)
SHOW INDEX FROM seckill_db_0.order_0;