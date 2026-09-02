-- M3 日常指令补齐子批：模板数据边界、owner 命名空间与全量清除幂等事实。
-- 当前版本不自动学习日常指令；未来写入方必须先锁定同一 owner 命名空间。

CREATE TABLE routine_command_namespace (
    owner_user_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_user_id),
    CONSTRAINT fk_routine_command_namespace_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_routine_command_namespace_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE routine_command_template (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    intent VARCHAR(30) NOT NULL,
    dialect_code VARCHAR(40) NOT NULL,
    dialect_package_version VARCHAR(60) NOT NULL,
    template_model_version VARCHAR(60) NOT NULL,
    threshold_version VARCHAR(60) NOT NULL,
    template_cipher MEDIUMBLOB NOT NULL,
    template_digest BINARY(32) NOT NULL,
    usage_count INT NOT NULL DEFAULT 0,
    last_confirmed_at DATETIME(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_routine_template_owner_status (owner_user_id, status),
    KEY idx_routine_template_owner_confirmed (owner_user_id, last_confirmed_at),
    CONSTRAINT fk_routine_template_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_routine_template_intent CHECK (
        intent IN ('SEND_MESSAGE', 'VOICE_CALL', 'VIDEO_CALL')
    ),
    CONSTRAINT chk_routine_template_status CHECK (
        status IN ('ACTIVE', 'INCOMPATIBLE')
    ),
    CONSTRAINT chk_routine_template_usage CHECK (usage_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE routine_command_deletion (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    expected_version BIGINT NULL,
    deleted_count INT NOT NULL,
    namespace_version_after BIGINT NOT NULL,
    deleted_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_routine_deletion_owner_idempotency (
        owner_user_id, idempotency_key_hash
    ),
    KEY idx_routine_deletion_owner_created (owner_user_id, created_at),
    CONSTRAINT fk_routine_deletion_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_routine_deletion_expected_version CHECK (
        expected_version IS NULL OR expected_version >= 1
    ),
    CONSTRAINT chk_routine_deletion_count CHECK (
        deleted_count >= 0 AND deleted_count <= 30
    ),
    CONSTRAINT chk_routine_deletion_namespace_version CHECK (
        namespace_version_after >= 2
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
