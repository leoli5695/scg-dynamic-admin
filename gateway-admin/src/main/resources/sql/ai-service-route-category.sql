-- AI 网关：给 services / routes 增加 category 判别列（NORMAL / AI）
-- prod ddl-auto=validate 需手动执行；dev=update 会自动加列，本文件用于 prod 及一致性对齐。
-- 对应实体: ServiceEntity.category / RouteEntity.category
--
-- 说明：MySQL 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，本脚本按“只执行一次”设计。
-- 若列已存在会报 1060 Duplicate column name，可忽略该行错误继续。

ALTER TABLE services
    ADD COLUMN category VARCHAR(16) NULL DEFAULT 'NORMAL' COMMENT 'NORMAL 普通服务 / AI 大模型服务' AFTER service_name;

ALTER TABLE services
    ADD INDEX idx_service_category_instance (category, instance_id);

ALTER TABLE routes
    ADD COLUMN category VARCHAR(16) NULL DEFAULT 'NORMAL' COMMENT 'NORMAL 普通路由 / AI 路由' AFTER route_id;

ALTER TABLE routes
    ADD INDEX idx_route_category_instance (category, instance_id);

-- 存量数据回填为 NORMAL（新列默认值已覆盖，此句用于历史 NULL 兜底）
UPDATE services SET category = 'NORMAL' WHERE category IS NULL;
UPDATE routes   SET category = 'NORMAL' WHERE category IS NULL;
