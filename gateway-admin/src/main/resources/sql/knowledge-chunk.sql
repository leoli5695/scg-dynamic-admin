-- 知识库分块表（RAG，prod ddl-auto=validate 需手动执行；dev=update 自动建）
-- 对应实体: com.leoli.gateway.admin.entity.KnowledgeChunkEntity
-- 相似度不在 DB 计算（由 VectorIndex 内存暴力检索），故无向量索引。
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    source_type  VARCHAR(32)  NOT NULL COMMENT 'runbook/incident/feedback/doc',
    source_ref   VARCHAR(255) NULL,
    title        VARCHAR(512) NULL,
    content      TEXT         NOT NULL,
    embedding    MEDIUMBLOB   NULL COMMENT 'float[dim] 小端字节',
    dim          INT          NULL,
    model        VARCHAR(64)  NULL,
    content_hash VARCHAR(64)  NULL,
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   DATETIME     NULL,
    updated_at   DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_kc_type_enabled (source_type, enabled),
    KEY idx_kc_hash (content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI Copilot 知识库分块(RAG)';
