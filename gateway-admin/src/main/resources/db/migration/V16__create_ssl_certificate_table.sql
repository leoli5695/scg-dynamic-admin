-- Create ssl_certificate table for SSL/TLS certificate management
-- Supports PEM and JKS/P12 certificate formats with hot-reload capability

CREATE TABLE IF NOT EXISTS ssl_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(36) COMMENT 'Associated gateway instance UUID for configuration isolation',
    cert_name VARCHAR(100) NOT NULL COMMENT 'Certificate display name',
    domain VARCHAR(255) NOT NULL COMMENT 'Domain/SNI this certificate is for',
    cert_type VARCHAR(20) DEFAULT 'PEM' COMMENT 'Certificate type: PEM, JKS, P12',
    cert_content MEDIUMTEXT COMMENT 'Certificate content (for PEM: the .crt/.pem file content)',
    key_content MEDIUMTEXT COMMENT 'Private key content (for PEM: the .key file content)',
    keystore_content MEDIUMTEXT COMMENT 'Keystore content (for JKS/P12: base64 encoded)',
    keystore_password VARCHAR(255) COMMENT 'Keystore password (encrypted)',
    issuer VARCHAR(255) COMMENT 'Certificate issuer',
    serial_number VARCHAR(100) COMMENT 'Certificate serial number',
    valid_from DATETIME COMMENT 'Certificate valid from date',
    valid_to DATETIME COMMENT 'Certificate expiry date',
    days_to_expiry INT COMMENT 'Days until expiry (auto-calculated)',
    status VARCHAR(20) DEFAULT 'VALID' COMMENT 'Certificate status: VALID, EXPIRED, EXPIRING_SOON',
    associated_routes TEXT COMMENT 'Associated routes (JSON array of route IDs)',
    enabled BOOLEAN DEFAULT TRUE COMMENT 'Whether this certificate is enabled',
    auto_renew BOOLEAN DEFAULT FALSE COMMENT 'Whether auto-renewal is enabled',
    last_renewed_at DATETIME COMMENT 'Last renewal time',
    created_at DATETIME COMMENT 'Creation timestamp',
    updated_at DATETIME COMMENT 'Last update timestamp',
    
    INDEX idx_instance_id (instance_id),
    INDEX idx_domain (domain),
    INDEX idx_status (status),
    INDEX idx_enabled (enabled),
    INDEX idx_valid_to (valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSL/TLS certificates for dynamic hot-reload';