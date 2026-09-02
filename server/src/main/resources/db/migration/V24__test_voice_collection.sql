-- 封闭测试语音采集：独立授权、独立音频用途、三十天硬上限和可删除样本登记。
-- 不保存转写、提示词正文、训练标签或原始音频；training_eligible 本版本固定为 0。

ALTER TABLE consent_record DROP CHECK chk_consent_type;
ALTER TABLE consent_record ADD CONSTRAINT chk_consent_type CHECK (
    type IN ('BASIC_IDENTITY', 'MICROPHONE', 'NOTIFICATION', 'ACCESSIBILITY',
             'VOICE_TEMPLATE', 'TASK_AUDIO', 'TEST_VOICE_COLLECTION')
);

ALTER TABLE audio_object DROP CHECK chk_audio_object_purpose;
ALTER TABLE audio_object ADD CONSTRAINT chk_audio_object_purpose CHECK (
    purpose IN ('TASK', 'ALIAS_ENROLLMENT', 'SAFETY_COMMAND_ENROLLMENT',
                'TEST_VOICE_COLLECTION')
);

CREATE TABLE voice_collection_sample (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    audio_object_id BINARY(16) NOT NULL,
    category VARCHAR(32) NULL,
    prompt_code VARCHAR(64) NULL,
    environment VARCHAR(24) NULL,
    dialect_code VARCHAR(40) NULL,
    consent_policy_version VARCHAR(60) NOT NULL,
    training_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL,
    create_idempotency_key_hash BINARY(32) NOT NULL,
    create_request_hash BINARY(32) NOT NULL,
    delete_idempotency_key_hash BINARY(32) NULL,
    delete_request_hash BINARY(32) NULL,
    retention_until DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_voice_collection_audio (audio_object_id),
    UNIQUE KEY uk_voice_collection_create_idempotency (
        owner_user_id, create_idempotency_key_hash
    ),
    UNIQUE KEY uk_voice_collection_delete_idempotency (
        owner_user_id, delete_idempotency_key_hash
    ),
    KEY idx_voice_collection_owner_status_created (
        owner_user_id, status, created_at
    ),
    KEY idx_voice_collection_retention (status, retention_until),
    CONSTRAINT fk_voice_collection_owner FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id),
    CONSTRAINT fk_voice_collection_audio FOREIGN KEY (audio_object_id)
        REFERENCES audio_object (id) ON DELETE CASCADE,
    CONSTRAINT chk_voice_collection_category CHECK (
        category IS NULL OR category IN (
            'WAKE_WORD', 'CONTACT_ALIAS', 'SAFETY_COMMAND', 'FULL_TASK', 'NEGATIVE'
        )
    ),
    CONSTRAINT chk_voice_collection_environment CHECK (
        environment IS NULL OR environment IN ('QUIET', 'HOME_NOISE', 'OUTDOOR')
    ),
    CONSTRAINT chk_voice_collection_status CHECK (
        status IN ('ACTIVE', 'DELETE_REQUESTED')
    ),
    CONSTRAINT chk_voice_collection_training_disabled CHECK (training_eligible = FALSE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
