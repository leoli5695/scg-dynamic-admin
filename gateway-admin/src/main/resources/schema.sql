-- Gateway configuration database schema

-- Routes table
CREATE TABLE IF NOT EXISTS routes (
    id VARCHAR(255) PRIMARY KEY,
    uri VARCHAR(1024) NOT NULL,
    predicates TEXT,
    filters TEXT,
    metadata TEXT,
    order_num INT DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    description VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Services table
CREATE TABLE IF NOT EXISTS services (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    load_balancer VARCHAR(50) DEFAULT 'round_robin',
    health_check_url VARCHAR(1024),
    metadata TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    description VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Plugins table
CREATE TABLE IF NOT EXISTS plugins (
    id VARCHAR(255) PRIMARY KEY,
    plugin_type VARCHAR(100) NOT NULL,
    route_id VARCHAR(255),
    config TEXT NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    priority INT DEFAULT 0,
    description VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Audit logs table
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator VARCHAR(100),
    operation_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(255),
    old_value TEXT,
    new_value TEXT,
    ip_address VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_routes_enabled ON routes(enabled);
CREATE INDEX IF NOT EXISTS idx_services_enabled ON services(enabled);
CREATE INDEX IF NOT EXISTS idx_plugins_route_id ON plugins(route_id);
CREATE INDEX IF NOT EXISTS idx_plugins_type ON plugins(plugin_type);
CREATE INDEX IF NOT EXISTS idx_audit_logs_target ON audit_logs(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs(created_at);
