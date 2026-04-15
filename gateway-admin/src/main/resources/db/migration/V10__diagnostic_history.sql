-- 诊断历史记录表
-- 用于存储历史诊断结果，支持趋势分析和历史对比

CREATE TABLE IF NOT EXISTS diagnostic_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(64) COMMENT '网关实例ID（可选，null表示全局诊断）',
    diagnostic_type VARCHAR(20) NOT NULL COMMENT '诊断类型: FULL, QUICK',
    overall_score INT NOT NULL COMMENT '健康评分 (0-100)',
    status VARCHAR(20) NOT NULL COMMENT '状态: HEALTHY, WARNING, CRITICAL',
    duration_ms BIGINT COMMENT '诊断耗时（毫秒）',
    
    -- 各组件状态快照（JSON格式）
    database_status VARCHAR(20) COMMENT '数据库状态',
    redis_status VARCHAR(20) COMMENT 'Redis状态',
    config_center_status VARCHAR(20) COMMENT '配置中心状态',
    routes_status VARCHAR(20) COMMENT '路由状态',
    auth_status VARCHAR(20) COMMENT '认证状态',
    gateway_instances_status VARCHAR(20) COMMENT '网关实例状态',
    performance_status VARCHAR(20) COMMENT '性能状态',
    
    -- 关键指标快照（用于趋势分析）
    gateway_qps DOUBLE COMMENT '网关 QPS',
    gateway_error_rate DOUBLE COMMENT '网关错误率 (%)',
    gateway_avg_latency_ms DOUBLE COMMENT '网关平均延迟 (ms)',
    gateway_heap_usage_percent DOUBLE COMMENT '网关堆内存使用率 (%)',
    gateway_cpu_usage_percent DOUBLE COMMENT '网关 CPU 使用率 (%)',
    admin_heap_usage_percent DOUBLE COMMENT 'Admin 服务堆内存使用率 (%)',
    
    -- 建议措施摘要
    recommendations_count INT COMMENT '建议措施数量',
    recommendations_summary TEXT COMMENT '建议措施摘要（JSON数组）',
    
    -- 元数据
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '诊断时间',
    
    INDEX idx_instance_id (instance_id),
    INDEX idx_created_at (created_at),
    INDEX idx_diagnostic_type (diagnostic_type),
    INDEX idx_overall_score (overall_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诊断历史记录表';