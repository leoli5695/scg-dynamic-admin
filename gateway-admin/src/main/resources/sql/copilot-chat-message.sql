-- AI Copilot 对话消息表（会话持久化，prod ddl-auto=validate 需手动执行；dev=update 自动建）
-- 对应实体: com.leoli.gateway.admin.entity.CopilotChatMessageEntity
CREATE TABLE IF NOT EXISTS copilot_chat_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  VARCHAR(128) NOT NULL,
    role        VARCHAR(16)  NOT NULL COMMENT 'user/assistant/system',
    content     TEXT         NULL,
    tool_digest TEXT         NULL COMMENT '该轮工具调用摘要，供多轮追问引用',
    operator    VARCHAR(64)  NULL,
    created_at  DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_ccm_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI Copilot 对话消息(持久化)';
