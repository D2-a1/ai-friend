-- 第一批开发：账号、token family、追加式授权、审计与撤回 outbox。
-- 所有时间按 UTC 写入 DATETIME(3)，所有主体主键使用 BINARY(16)。

CREATE TABLE app_user (
    id BINARY(16) NOT NULL,
    wechat_open_id_cipher VARBINARY(512) NOT NULL,
    wechat_open_id_hash BINARY(32) NOT NULL,
    wechat_union_id_cipher VARBINARY(512) NULL,
    wechat_union_id_hash BINARY(32) NULL,
    status VARCHAR(20) NOT NULL,
    account_generation BIGINT NOT NULL,
    dialect_preference VARCHAR(30) NULL,
    accessibility_settings JSON NOT NULL,
    alias_namespace_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_wechat_open_id_hash (wechat_open_id_hash),
    KEY idx_app_user_status (status),
    CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'DELETING', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE token_family (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_token_family_user_status (user_id, status),
    CONSTRAINT fk_token_family_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT chk_token_family_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refresh_token (
    id BINARY(16) NOT NULL,
    family_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    rotated_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_token_family_status (family_id, status),
    KEY idx_refresh_token_user_expires (user_id, expires_at),
    CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES token_family (id),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT chk_refresh_token_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE consent_record (
    id BINARY(16) NOT NULL,
    sequence_no BIGINT NOT NULL AUTO_INCREMENT,
    user_id BINARY(16) NOT NULL,
    type VARCHAR(40) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    decided_at DATETIME(3) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consent_sequence_no (sequence_no),
    UNIQUE KEY uk_consent_idempotency (user_id, type, idempotency_key_hash),
    KEY idx_consent_user_type_sequence (user_id, type, sequence_no),
    CONSTRAINT fk_consent_record_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT chk_consent_type CHECK (
        type IN ('BASIC_IDENTITY', 'MICROPHONE', 'NOTIFICATION', 'ACCESSIBILITY', 'VOICE_TEMPLATE', 'TASK_AUDIO')
    ),
    CONSTRAINT chk_consent_decision CHECK (decision IN ('GRANTED', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_event (
    id BINARY(16) NOT NULL,
    actor_id_hash BINARY(32) NOT NULL,
    action VARCHAR(60) NOT NULL,
    result VARCHAR(30) NOT NULL,
    reason_code VARCHAR(60) NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_event_action_created (action, created_at),
    KEY idx_audit_event_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    id BINARY(16) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_status_available (status, available_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
