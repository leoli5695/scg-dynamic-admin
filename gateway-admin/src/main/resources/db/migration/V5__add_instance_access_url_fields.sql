-- Add access URL fields for gateway instances
-- Supports multiple access URL sources with priority

-- Manual configured access URL (highest priority, for SLB/custom domain)
ALTER TABLE gateway_instances ADD COLUMN manual_access_url VARCHAR(255) COMMENT '手动配置的访问地址(SLB/域名/自定义地址)';

-- Discovered access URL from K8s Service (LoadBalancer IP or NodePort)
ALTER TABLE gateway_instances ADD COLUMN discovered_access_url VARCHAR(255) COMMENT 'K8s自动发现的访问地址(LoadBalancer/NodePort)';

-- Reported access URL from gateway heartbeat (local dev, ECS direct)
ALTER TABLE gateway_instances ADD COLUMN reported_access_url VARCHAR(255) COMMENT '心跳上报的访问地址';

-- Add comments for existing related fields
ALTER TABLE gateway_instances MODIFY COLUMN server_port INT COMMENT '网关HTTP服务端口';
ALTER TABLE gateway_instances MODIFY COLUMN node_ip VARCHAR(64) COMMENT 'K8s节点IP或ECS IP';
ALTER TABLE gateway_instances MODIFY COLUMN node_port INT COMMENT 'K8s NodePort端口';