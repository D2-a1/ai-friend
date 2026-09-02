-- 第五批开发：联系人绑定持久化基础与 owner 范围只读查询。
-- 微信主体、稳定定位和备注按敏感级别保存；本表不保存好友列表或聊天正文。

CREATE TABLE contact_binding (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    contact_subject_hash BINARY(32) NOT NULL,
    contact_subject_cipher VARBINARY(512) NOT NULL,
    wechat_locator_cipher VARBINARY(512) NULL,
    wechat_locator_hash BINARY(32) NULL,
    remark_cipher VARBINARY(512) NULL,
    local_verification_version VARCHAR(40) NULL,
    verified_at DATETIME(3) NULL,
    relationship VARCHAR(30) NULL,
    status VARCHAR(30) NOT NULL,
    created_by BINARY(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contact_binding_owner_subject (owner_user_id, contact_subject_hash),
    KEY idx_contact_binding_owner_status_updated (owner_user_id, status, updated_at),
    KEY idx_contact_binding_locator_hash (wechat_locator_hash),
    CONSTRAINT fk_contact_binding_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_contact_binding_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT chk_contact_binding_status CHECK (
        status IN (
            'PENDING_CONSENT', 'PENDING_LOCAL_VERIFY', 'ACTIVE_NO_ALIAS',
            'ACTIVE', 'REVERIFY_REQUIRED', 'REVOKED', 'BLOCKED'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
