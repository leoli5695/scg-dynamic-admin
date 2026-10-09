-- AI Copilot 反馈表（prod ddl-auto=validate 需手动执行；dev=update 会自动建）
-- 对应实体: com.leoli.gateway.admin.entity.CopilotFeedbackEntity
CREATE TABLE IF NOT EXISTS copilot_feedback (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    session_id    VARCHAR(128) NULL,
    question      VARCHAR(4000) NULL,
    answer        TEXT         NULL,
    rating        VARCHAR(8)   NOT NULL COMMENT 'UP / DOWN',
    user_comment  VARCHAR(1000) NULL,
    operator_name VARCHAR(64)  NULL,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_copilot_fb_session (session_id),
    KEY idx_copilot_fb_rating (rating),
    KEY idx_copilot_fb_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI Copilot 用户反馈';
