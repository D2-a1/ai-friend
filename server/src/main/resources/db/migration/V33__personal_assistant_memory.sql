-- 可选长期个人偏好：仅保存三个有限枚举的 AES-GCM 密文和最小幂等元数据。
-- 不保存消息正文、任务音频、联系人映射、自由文本或敏感属性。

ALTER TABLE consent_record DROP CHECK chk_consent_type;
ALTER TABLE consent_record ADD CONSTRAINT chk_consent_type CHECK (
    type IN ('BASIC_IDENTITY', 'MICROPHONE', 'NOTIFICATION', 'ACCESSIBILITY',
             'VOICE_TEMPLATE', 'TASK_AUDIO', 'TEST_VOICE_COLLECTION',
             'VOICE_MODEL_TRAINING', 'PERSONAL_MEMORY')
);

CREATE TABLE personal_assistant_memory (
    owner_user_id BINARY(16) NOT NULL,
    preferences_cipher VARBINARY(1024) NULL,
    preferences_digest BINARY(32) NULL,
    policy_version VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    update_idempotency_key_hash BINARY(32) NULL,
    update_request_hash BINARY(32) NULL,
    delete_idempotency_key_hash BINARY(32) NULL,
    delete_request_hash BINARY(32) NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    PRIMARY KEY (owner_user_id),
    CONSTRAINT fk_personal_memory_owner FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id),
    CONSTRAINT chk_personal_memory_status CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_personal_memory_version CHECK (version >= 1),
    CONSTRAINT chk_personal_memory_material CHECK (
        (status = 'ACTIVE' AND preferences_cipher IS NOT NULL
            AND preferences_digest IS NOT NULL AND deleted_at IS NULL)
        OR
        (status = 'DELETED' AND preferences_cipher IS NULL
            AND preferences_digest IS NULL AND deleted_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
