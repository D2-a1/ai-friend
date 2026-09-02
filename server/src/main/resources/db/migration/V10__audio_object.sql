-- M3 第一子批：受限音频上传凭证、一次性对象元数据和幂等持久化。
-- 原始音频本体不进入 MySQL；对象键使用应用层 AES-GCM 密文保存，上传秘密只保存 SHA-256 摘要。

CREATE TABLE audio_object (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    media_type VARCHAR(40) NOT NULL,
    expected_size_bytes BIGINT NOT NULL,
    expected_duration_ms INT NOT NULL,
    expected_sha256 BINARY(32) NOT NULL,
    object_key_cipher VARBINARY(1024) NOT NULL,
    upload_token_hash BINARY(32) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    upload_expires_at DATETIME(3) NOT NULL,
    retention_until DATETIME(3) NOT NULL,
    storage_version VARCHAR(128) NULL,
    uploaded_at DATETIME(3) NULL,
    consumed_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audio_object_owner_idempotency (owner_user_id, idempotency_key_hash),
    KEY idx_audio_object_owner_status_created (owner_user_id, status, created_at),
    KEY idx_audio_object_status_expires (status, upload_expires_at),
    KEY idx_audio_object_retention (status, retention_until),
    CONSTRAINT fk_audio_object_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_audio_object_purpose CHECK (
        purpose IN ('TASK', 'ALIAS_ENROLLMENT', 'SAFETY_COMMAND_ENROLLMENT')
    ),
    CONSTRAINT chk_audio_object_media_type CHECK (
        media_type IN ('audio/mp4', 'audio/aac', 'audio/wav', 'audio/ogg')
    ),
    CONSTRAINT chk_audio_object_size CHECK (
        expected_size_bytes BETWEEN 1 AND 20971520
    ),
    CONSTRAINT chk_audio_object_duration CHECK (
        expected_duration_ms BETWEEN 200 AND 60000
    ),
    CONSTRAINT chk_audio_object_status CHECK (
        status IN ('ISSUED', 'CONSUMED', 'EXPIRED', 'DELETED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
