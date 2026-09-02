-- 第三批开发：proof 单次兑换与固定 30 分钟受限邀请会话。
-- Cookie、CSRF、OAuth state 只保存 SHA-256 摘要，任何明文均不得落库。

CREATE TABLE invitation_session (
    id BINARY(16) NOT NULL,
    invitation_id BINARY(16) NOT NULL,
    session_token_hash BINARY(32) NOT NULL,
    csrf_token_hash BINARY(32) NOT NULL,
    oauth_state_hash BINARY(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    terminated_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invitation_session_invitation (invitation_id),
    UNIQUE KEY uk_invitation_session_token (session_token_hash),
    UNIQUE KEY uk_invitation_session_csrf (csrf_token_hash),
    UNIQUE KEY uk_invitation_session_oauth_state (oauth_state_hash),
    KEY idx_invitation_session_status_expiry (status, expires_at),
    CONSTRAINT fk_invitation_session_invitation FOREIGN KEY (invitation_id) REFERENCES contact_invitation (id),
    CONSTRAINT chk_invitation_session_status CHECK (
        status IN ('AWAITING_WECHAT_OAUTH', 'WECHAT_VERIFIED', 'TERMINATED', 'EXPIRED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
