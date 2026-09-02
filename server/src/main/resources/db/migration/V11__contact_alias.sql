-- M3 第三子批：联系人方言称呼、声学模板密文与创建/删除幂等状态。
-- 辅助文字、音素提示和声学模板只保存 AES-GCM 密文；相似度唯一性由 owner 锁内的声学端口判断。

CREATE TABLE contact_alias (
    id BINARY(16) NOT NULL,
    binding_id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    display_text_cipher VARBINARY(512) NULL,
    phonetic_hint_cipher VARBINARY(512) NULL,
    dialect_code VARCHAR(40) NOT NULL,
    dialect_package_version VARCHAR(60) NOT NULL,
    template_model_version VARCHAR(60) NOT NULL,
    threshold_version VARCHAR(60) NOT NULL,
    template_cipher MEDIUMBLOB NULL,
    template_digest BINARY(32) NULL,
    status VARCHAR(20) NOT NULL,
    create_idempotency_key_hash BINARY(32) NOT NULL,
    create_request_hash BINARY(32) NOT NULL,
    delete_idempotency_key_hash BINARY(32) NULL,
    delete_request_hash BINARY(32) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contact_alias_owner_create_idempotency (
        owner_user_id, create_idempotency_key_hash
    ),
    KEY idx_contact_alias_binding_status (binding_id, status),
    KEY idx_contact_alias_owner_status (owner_user_id, status),
    KEY idx_contact_alias_template_version (
        owner_user_id, template_model_version, threshold_version
    ),
    CONSTRAINT fk_contact_alias_binding FOREIGN KEY (binding_id) REFERENCES contact_binding (id),
    CONSTRAINT fk_contact_alias_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_contact_alias_status CHECK (status IN ('ACTIVE', 'DELETED', 'INCOMPATIBLE')),
    CONSTRAINT chk_contact_alias_active_material CHECK (
        status <> 'ACTIVE'
        OR (display_text_cipher IS NOT NULL AND template_cipher IS NOT NULL AND template_digest IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
