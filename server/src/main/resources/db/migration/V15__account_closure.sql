-- M8 第二子批 B：账号注销可靠受理、72 小时重新注册下限和幂等恢复事实。
CREATE TABLE account_closure_request (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    account_generation BIGINT NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    re_registration_not_before DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_closure_owner (owner_user_id),
    UNIQUE KEY uk_account_closure_owner_key (owner_user_id, idempotency_key_hash),
    KEY idx_account_closure_status_accepted (status, accepted_at),
    CONSTRAINT fk_account_closure_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_account_closure_status CHECK (status IN ('ACCEPTED', 'COMPLETED')),
    CONSTRAINT chk_account_closure_generation CHECK (account_generation >= 1),
    CONSTRAINT chk_account_closure_re_registration CHECK (
        re_registration_not_before >= accepted_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
