-- ========================================
-- Gateway 数据库升级脚本 V2
-- ========================================
-- 功能：删除 SERVICE_INSTANCES 表的 ENABLED 列
-- 原因：enabled 字段现在由 Nacos 配置控制，数据库只负责存储运行时健康状态
-- 日期：2026-03-16
-- 影响：
--   1. ServiceInstanceHealth 实体移除 enabled 字段
--   2. enabled 状态完全由 Nacos 配置驱动
--   3. 数据库只存储 healthy 健康状态
-- ========================================

-- 删除 ENABLED 列
ALTER TABLE PUBLIC.SERVICE_INSTANCES DROP COLUMN IF EXISTS ENABLED;

-- 验证：检查列是否已删除
SELECT COLUMN_NAME, DATA_TYPE 
FROM INFORMATION_SCHEMA.COLUMNS 
WHERE TABLE_NAME = 'SERVICE_INSTANCES' 
AND COLUMN_NAME = 'ENABLED';
-- 预期结果：0 行（列已删除）

-- 查看表结构
DESCRIBE PUBLIC.SERVICE_INSTANCES;
-- 预期：不再包含 ENABLED 列
