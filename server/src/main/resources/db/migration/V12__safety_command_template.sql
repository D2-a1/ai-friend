-- M3 安全指令子批：四类方言安全指令整批注册、替换和幂等墓碑。
-- 原始音频不进 MySQL；模板只保存 AES-GCM 密文，被替换时立即清空可解密材料。

CREATE TABLE safety_command_namespace (
    owner_user_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_user_id),
    CONSTRAINT fk_safety_command_namespace_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE safety_command_enrollment (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    consent_policy_version VARCHAR(40) NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_safety_enrollment_owner_idempotency (
        owner_user_id, idempotency_key_hash
    ),
    KEY idx_safety_enrollment_owner_completed (owner_user_id, completed_at),
    CONSTRAINT fk_safety_enrollment_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE safety_command_template (
    id BINARY(16) NOT NULL,
    enrollment_id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    command_type VARCHAR(30) NOT NULL,
    dialect_code VARCHAR(40) NOT NULL,
    dialect_package_version VARCHAR(60) NOT NULL,
    template_model_version VARCHAR(60) NOT NULL,
    threshold_version VARCHAR(60) NOT NULL,
    template_cipher MEDIUMBLOB NULL,
    template_digest BINARY(32) NULL,
    status VARCHAR(20) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    replaced_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_safety_template_owner_type_active (
        owner_user_id, command_type, active_slot
    ),
    KEY idx_safety_template_owner_status (owner_user_id, status),
    KEY idx_safety_template_enrollment (enrollment_id),
    CONSTRAINT fk_safety_template_enrollment
        FOREIGN KEY (enrollment_id) REFERENCES safety_command_enrollment (id),
    CONSTRAINT fk_safety_template_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_safety_template_type CHECK (
        command_type IN ('CONFIRM_SEND', 'CONFIRM_CALL', 'CANCEL', 'REJECT_RETRY')
    ),
    CONSTRAINT chk_safety_template_status CHECK (status IN ('ACTIVE', 'REPLACED')),
    CONSTRAINT chk_safety_template_material CHECK (
        (status = 'ACTIVE' AND template_cipher IS NOT NULL
            AND template_digest IS NOT NULL AND replaced_at IS NULL)
        OR (status = 'REPLACED' AND template_cipher IS NULL
            AND template_digest IS NULL AND replaced_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
