-- ============================================================================
-- 秒杀系统分库分表初始化脚本
-- ============================================================================
-- 
-- 分库: 8个 (seckill_db_0 ~ seckill_db_7)
-- 分表: 16张 (order_0 ~ order_15)
-- 分片键: user_id
-- 
-- 路由规则:
--   库: user_id % 8
--   表: user_id % 16
-- 
-- 注意:
-- 1. 需在每个数据库中创建对应的表
-- 2. 唯一索引防止重复下单
-- 3. 事务日志表存储在ds_0（不分片）
-- ============================================================================

-- ============================================================================
-- 创建数据库
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
-- 订单表结构（每个库8张表）
-- ============================================================================
-- db_0 存储: order_0, order_2, order_4, order_6, order_8, order_10, order_12, order_14
-- db_1 存储: order_1, order_3, order_5, order_7, order_9, order_11, order_13, order_15
-- ...

-- 在 seckill_db_0 中创建偶数表
USE seckill_db_0;

CREATE TABLE IF NOT EXISTS `order_0` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号(雪花算法)',
  `user_id` BIGINT NOT NULL COMMENT '用户ID(分片键)',
  `seckill_id` BIGINT NOT NULL COMMENT '秒杀活动ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '购买数量',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '总金额',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0:待支付 1:已支付 2:已取消 3:已退款',
  `shard_index` INT NOT NULL DEFAULT 0 COMMENT '库存分片索引',
  `pay_channel` VARCHAR(32) COMMENT '支付渠道',
  `pay_time` DATETIME COMMENT '支付时间',
  `refund_time` DATETIME COMMENT '退款时间',
  `refund_reason` VARCHAR(255) COMMENT '退款原因',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_seckill` (`user_id`, `seckill_id`) COMMENT '防重唯一索引',
  UNIQUE KEY `uk_order_no` (`order_no`) COMMENT '订单号唯一索引',
  KEY `idx_seckill_id` (`seckill_id`) COMMENT '秒杀活动索引',
  KEY `idx_create_time` (`create_time`) COMMENT '创建时间索引',
  KEY `idx_status` (`status`) COMMENT '状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单表-分片0';

CREATE TABLE IF NOT EXISTS `order_2` LIKE `order_0`;
CREATE TABLE IF NOT EXISTS `order_4` LIKE `order_0`;
CREATE TABLE IF NOT EXISTS `order_6` LIKE `order_0`;
CREATE TABLE IF NOT EXISTS `order_8` LIKE `order_0`;
CREATE TABLE IF NOT EXISTS `order_10` LIKE `order_0`;
CREATE TABLE IF NOT EXISTS `order_12` LIKE `order_0`;
CREATE TABLE IF NOT EXISTS `order_14` LIKE `order_0`;

-- 在 seckill_db_1 中创建奇数表
USE seckill_db_1;

CREATE TABLE IF NOT EXISTS `order_1` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号(雪花算法)',
  `user_id` BIGINT NOT NULL COMMENT '用户ID(分片键)',
  `seckill_id` BIGINT NOT NULL COMMENT '秒杀活动ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '购买数量',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '总金额',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0:待支付 1:已支付 2:已取消 3:已退款',
  `shard_index` INT NOT NULL DEFAULT 0 COMMENT '库存分片索引',
  `pay_channel` VARCHAR(32) COMMENT '支付渠道',
  `pay_time` DATETIME COMMENT '支付时间',
  `refund_time` DATETIME COMMENT '退款时间',
  `refund_reason` VARCHAR(255) COMMENT '退款原因',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_seckill` (`user_id`, `seckill_id`) COMMENT '防重唯一索引',
  UNIQUE KEY `uk_order_no` (`order_no`) COMMENT '订单号唯一索引',
  KEY `idx_seckill_id` (`seckill_id`) COMMENT '秒杀活动索引',
  KEY `idx_create_time` (`create_time`) COMMENT '创建时间索引',
  KEY `idx_status` (`status`) COMMENT '状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单表-分片1';

CREATE TABLE IF NOT EXISTS `order_3` LIKE `order_1`;
CREATE TABLE IF NOT EXISTS `order_5` LIKE `order_1`;
CREATE TABLE IF NOT EXISTS `order_7` LIKE `order_1`;
CREATE TABLE IF NOT EXISTS `order_9` LIKE `order_1`;
CREATE TABLE IF NOT EXISTS `order_11` LIKE `order_1`;
CREATE TABLE IF NOT EXISTS `order_13` LIKE `order_1`;
CREATE TABLE IF NOT EXISTS `order_15` LIKE `order_1`;

-- 其他数据库同理创建对应的表...
-- db_2: order_0, order_2, order_4, order_6, order_8, order_10, order_12, order_14
-- db_3: order_1, order_3, order_5, order_7, order_9, order_11, order_13, order_15
-- ...

-- ============================================================================
-- 事务日志表（存储在ds_0，不分片）
-- ============================================================================
USE seckill_db_0;

CREATE TABLE IF NOT EXISTS `transaction_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `transaction_id` VARCHAR(64) NOT NULL COMMENT '事务ID(半消息ID)',
  `order_no` VARCHAR(64) COMMENT '订单号',
  `seckill_id` BIGINT NOT NULL COMMENT '秒杀活动ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '购买数量',
  `total_amount` DECIMAL(10,2) NOT NULL COMMENT '总金额',
  `shard_index` INT NOT NULL DEFAULT 0 COMMENT '库存分片索引',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0:处理中 1:成功 2:失败',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  `error_msg` VARCHAR(512) COMMENT '错误信息',
  `trace_id` VARCHAR(64) COMMENT '链路追踪ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `expire_time` DATETIME NOT NULL COMMENT '过期时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transaction_id` (`transaction_id`) COMMENT '事务ID唯一索引',
  KEY `idx_user_seckill` (`user_id`, `seckill_id`) COMMENT '用户秒杀索引',
  KEY `idx_status` (`status`) COMMENT '状态索引',
  KEY `idx_create_time` (`create_time`) COMMENT '创建时间索引',
  KEY `idx_expire_time` (`expire_time`) COMMENT '过期时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事务日志表';

-- ============================================================================
-- 秒杀活动表（存储在ds_0，不分片）
-- ============================================================================
CREATE TABLE IF NOT EXISTS `seckill_activity` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `activity_name` VARCHAR(128) NOT NULL COMMENT '活动名称',
  `product_id` BIGINT NOT NULL COMMENT '商品ID',
  `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价格',
  `original_price` DECIMAL(10,2) NOT NULL COMMENT '原价',
  `total_stock` INT NOT NULL COMMENT '总库存',
  `sold_count` INT NOT NULL DEFAULT 0 COMMENT '已售数量',
  `start_time` DATETIME NOT NULL COMMENT '开始时间',
  `end_time` DATETIME NOT NULL COMMENT '结束时间',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0:未开始 1:进行中 2:已结束',
  `limit_per_user` INT NOT NULL DEFAULT 1 COMMENT '每人限购数量',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_product_id` (`product_id`) COMMENT '商品索引',
  KEY `idx_status` (`status`) COMMENT '状态索引',
  KEY `idx_start_time` (`start_time`) COMMENT '开始时间索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀活动表';

-- ============================================================================
-- 商品表（存储在ds_0，不分片）
-- ============================================================================
CREATE TABLE IF NOT EXISTS `seckill_product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_name` VARCHAR(128) NOT NULL COMMENT '商品名称',
  `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价格',
  `original_price` DECIMAL(10,2) NOT NULL COMMENT '原价',
  `stock` INT NOT NULL COMMENT '库存',
  `product_desc` VARCHAR(512) COMMENT '商品描述',
  `image_url` VARCHAR(255) COMMENT '商品图片',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0:下架 1:上架',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`) COMMENT '状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀商品表';

-- ============================================================================
-- 示例数据
-- ============================================================================
USE seckill_db_0;

-- 创建示例秒杀商品
INSERT INTO `seckill_product` (`id`, `product_name`, `seckill_price`, `original_price`, `stock`, `product_desc`, `status`) 
VALUES (1, 'iPhone 15 Pro', 5999.00, 8999.00, 1000, 'Apple iPhone 15 Pro 256GB', 1);

-- 创建示例秒杀活动
INSERT INTO `seckill_activity` (`id`, `activity_name`, `product_id`, `seckill_price`, `original_price`, `total_stock`, `start_time`, `end_time`, `status`, `limit_per_user`) 
VALUES (1, 'iPhone 15 Pro 秒杀活动', 1, 5999.00, 8999.00, 1000, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 2 HOUR, 1, 1);