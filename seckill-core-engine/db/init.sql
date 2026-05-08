-- ============================================================================
-- Seckill Core Engine - MySQL 初始化脚本
-- ============================================================================
-- 分库分表: 8库 × 16表 = 128个物理表
-- 分片规则: user_id % 8 分库, user_id % 16 分表

-- ============================================================================
-- 1. 创建8个分库
-- ============================================================================
CREATE DATABASE IF NOT EXISTS seckill_db_0 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_db_1 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_db_2 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_db_3 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_db_4 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_db_5 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_db_6 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_db_7 DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================
-- 2. 在每个库创建16个订单表
-- ============================================================================
-- 使用存储函数批量创建

DELIMITER //

-- 创建订单表的存储过程
CREATE PROCEDURE create_order_tables()
BEGIN
    DECLARE db_idx INT DEFAULT 0;
    DECLARE table_idx INT DEFAULT 0;
    DECLARE db_name VARCHAR(20);
    DECLARE table_name VARCHAR(20);
    DECLARE sql_text TEXT;
    
    -- 循环8个库
    WHILE db_idx < 8 DO
        SET db_name = CONCAT('seckill_db_', db_idx);
        
        -- 循环16个表
        SET table_idx = 0;
        WHILE table_idx < 16 DO
            SET table_name = CONCAT('order_', table_idx);
            
            SET sql_text = CONCAT('
                CREATE TABLE IF NOT EXISTS ', db_name, '.', table_name, ' (
                    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
                    `order_no`      VARCHAR(64)  NOT NULL COMMENT ''订单号(雪花算法)'',
                    `user_id`       BIGINT       NOT NULL COMMENT ''用户ID(分片键)'',
                    `seckill_id`    BIGINT       NOT NULL COMMENT ''秒杀活动ID'',
                    `product_id`    BIGINT       NOT NULL COMMENT ''商品ID'',
                    
                    `quantity`      INT          NOT NULL COMMENT ''购买数量'',
                    `total_amount`  DECIMAL(10,2) NOT NULL COMMENT ''总金额'',
                    
                    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT ''0:待支付 1:已支付 2:已取消 3:已退款'',
                    `pay_time`      DATETIME     COMMENT ''支付时间'',
                    `pay_channel`   VARCHAR(32)  COMMENT ''支付渠道(ALIPAY/WECHAT)'',
                    
                    -- 分片信息（用于回补）
                    `shard_index`   INT          NOT NULL COMMENT ''Redis分片索引'',
                    
                    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    
                    PRIMARY KEY (`id`),
                    
                    -- 【关键索引】防重唯一索引 (Layer 2防重机制)
                    UNIQUE KEY `uk_user_seckill` (`user_id`, `seckill_id`),
                    
                    -- 【补充索引】按订单号查询
                    UNIQUE KEY `uk_order_no` (`order_no`),
                    
                    -- 【补充索引】按创建时间查询
                    KEY `idx_create_time` (`create_time`),
                    
                    -- 【补充索引】订单状态查询
                    KEY `idx_status` (`status`)
                    
                    -- 注意: seckill_id 索引无效！跨库查询需用ES异构索引
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''秒杀订单表''
            ');
            
            EXECUTE sql_text;
            SET table_idx = table_idx + 1;
        END WHILE;
        
        SET db_idx = db_idx + 1;
    END WHILE;
END //

DELIMITER ;

-- 执行创建订单表
CALL create_order_tables();

-- ============================================================================
-- 3. 创建事务日志表 (存储在 ds_0)
-- ============================================================================
CREATE TABLE IF NOT EXISTS seckill_db_0.transaction_log (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  
  -- 事务标识
  `transaction_id`  VARCHAR(64)  NOT NULL COMMENT '事务ID(半消息ID)',
  `seckill_id`      BIGINT       NOT NULL COMMENT '秒杀活动ID',
  `user_id`         BIGINT       NOT NULL COMMENT '用户ID',
  `product_id`      BIGINT       NOT NULL COMMENT '商品ID',
  
  -- 业务数据
  `quantity`        INT          NOT NULL COMMENT '购买数量',
  `total_amount`    DECIMAL(10,2) NOT NULL COMMENT '总金额',
  `order_no`        VARCHAR(64)  COMMENT '订单号(预生成)',
  
  -- 分片信息（关键：用于回补库存）
  `shard_index`     INT          NOT NULL COMMENT '扣减的分片索引(用于回补)',
  
  -- 状态管理
  `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0:处理中 1:成功 2:失败',
  `retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '事务回查重试次数',
  `error_msg`       VARCHAR(255) COMMENT '失败原因记录',
  
  -- 链路追踪
  `trace_id`        VARCHAR(64)  COMMENT '链路追踪ID(网关生成)',
  
  -- 时间戳
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `expire_time`     DATETIME     NOT NULL COMMENT '事务过期时间(用于清理)',
  
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transaction_id` (`transaction_id`),
  KEY `idx_seckill_user` (`seckill_id`, `user_id`),
  KEY `idx_status_expire` (`status`, `expire_time`),
  KEY `idx_trace_id` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RocketMQ事务消息日志表';

-- ============================================================================
-- 4. 创建秒杀活动表 (存储在 ds_0)
-- ============================================================================
CREATE TABLE IF NOT EXISTS seckill_db_0.seckill_activity (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `activity_name`     VARCHAR(100) NOT NULL COMMENT '活动名称',
  `start_time`        DATETIME     NOT NULL COMMENT '开始时间',
  `end_time`          DATETIME     NOT NULL COMMENT '结束时间',
  `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '0:未开始 1:进行中 2:已结束 3:已取消',
  `total_stock`       INT          NOT NULL COMMENT '总库存',
  `shard_count`       INT          NOT NULL DEFAULT 8 COMMENT '分片数量',
  
  -- 活动配置
  `max_buy_count`     INT          NOT NULL DEFAULT 1 COMMENT '单人最大购买数量',
  `buy_limit_rate`    INT          NOT NULL DEFAULT 1000 COMMENT '购买限流速率(QPS)',
  
  -- 时间戳
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `warmed_up`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0:未预热 1:已预热',
  
  PRIMARY KEY (`id`),
  KEY `idx_start_time` (`start_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- ============================================================================
-- 5. 创建秒杀商品表 (存储在 ds_0)
-- ============================================================================
CREATE TABLE IF NOT EXISTS seckill_db_0.seckill_product (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seckill_id`        BIGINT       NOT NULL COMMENT '秒杀活动ID',
  `product_name`      VARCHAR(200) NOT NULL COMMENT '商品名称',
  `product_image`     VARCHAR(500) COMMENT '商品图片URL',
  `original_price`    DECIMAL(10,2) NOT NULL COMMENT '原价',
  `seckill_price`     DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
  `stock`             INT          NOT NULL COMMENT '库存数量',
  
  -- 商品状态
  `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '1:上架 0:下架',
  
  -- 时间戳
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  
  PRIMARY KEY (`id`),
  KEY `idx_seckill_id` (`seckill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- ============================================================================
-- 6. 创建库存对账表 (存储在 ds_0)
-- ============================================================================
CREATE TABLE IF NOT EXISTS seckill_db_0.stock_reconciliation (
  `id`            BIGINT PRIMARY KEY AUTO_INCREMENT,
  `seckill_id`    BIGINT NOT NULL COMMENT '秒杀活动ID',
  `redis_stock`   INT NOT NULL COMMENT 'Redis库存',
  `mysql_stock`   INT NOT NULL COMMENT 'MySQL计算库存',
  `diff`          INT NOT NULL COMMENT '差异值',
  `check_time`    DATETIME NOT NULL COMMENT '对账时间',
  `status`        TINYINT COMMENT '0:待处理 1:已修正',
  `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  KEY `idx_seckill_time` (`seckill_id`, `check_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存对账表';

-- ============================================================================
-- 7. 创建测试数据
-- ============================================================================
-- 创建测试秒杀活动
INSERT INTO seckill_db_0.seckill_activity 
(activity_name, start_time, end_time, status, total_stock, shard_count, max_buy_count)
VALUES 
('双十一秒杀测试', NOW(), DATE_ADD(NOW(), INTERVAL 1 HOUR), 1, 10000, 8, 1);

-- 创建测试商品
INSERT INTO seckill_db_0.seckill_product 
(seckill_id, product_name, original_price, seckill_price, stock, status)
VALUES 
(1, 'iPhone 15 Pro Max', 9999.00, 7999.00, 10000, 1);

-- ============================================================================
-- 8. ES索引初始化脚本 (通过curl执行)
-- ============================================================================
-- 注意：以下为ES索引创建命令，需要在ES启动后执行
-- 
-- curl -X PUT "http://localhost:30920/order_index" -H 'Content-Type: application/json' -d'
-- {
--   "mappings": {
--     "properties": {
--       "order_no":    { "type": "keyword" },
--       "user_id":     { "type": "long" },
--       "seckill_id":  { "type": "long" },
--       "status":      { "type": "integer" },
--       "create_time": { "type": "date" },
--       "total_amount":{ "type": "double" },
--       "quantity":    { "type": "integer" }
--     }
--   }
-- }'

-- ============================================================================
-- 清理存储过程
-- ============================================================================
DROP PROCEDURE IF EXISTS create_order_tables;