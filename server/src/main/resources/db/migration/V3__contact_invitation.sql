-- 第二批开发：亲友邀请创建与邀请人撤销。
-- proof 明文和完整分享地址不得落库；URL fragment 只返回创建方当前会话。

CREATE TABLE contact_invitation (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    proof_digest BINARY(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    create_idempotency_key_hash BINARY(32) NOT NULL,
    revoke_idempotency_key_hash BINARY(32) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contact_invitation_create_idempotency (owner_user_id, create_idempotency_key_hash),
    KEY idx_contact_invitation_owner_status_expiry (owner_user_id, status, expires_at),
    KEY idx_contact_invitation_owner_created (owner_user_id, created_at),
    CONSTRAINT fk_contact_invitation_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_contact_invitation_status CHECK (
        status IN (
            'PENDING', 'PROOF_REDEEMED', 'WECHAT_VERIFIED', 'ACCEPTED',
            'DECLINED', 'REVOKED', 'EXPIRED'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
