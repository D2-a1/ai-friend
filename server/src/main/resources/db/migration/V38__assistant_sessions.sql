-- 独立只读问答会话/持久幂等记录；本轮仅静态与模拟测试，未执行此迁移。
-- 上线前必须完成会话事务适配器、用途撤权/注销/过期清理与云端隔离库验证。
CREATE TABLE assistant_session (
    id BINARY(16) NOT NULL PRIMARY KEY,
    owner_user_id BINARY(16) NOT NULL,
    purpose VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    create_key_hash BINARY(32) NOT NULL,
    create_request_digest BINARY(32) NOT NULL,
    policy_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    accepted_questions INT NOT NULL,
    encrypted_context MEDIUMBLOB NULL,
    created_at DATETIME(3) NOT NULL,
    last_activity_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    pending_request_id BINARY(16) NULL,
    lease_token BINARY(16) NULL,
    pending_started_at DATETIME(3) NULL,
    pending_deadline DATETIME(3) NULL,
    UNIQUE KEY uq_assistant_creation (owner_user_id,create_key_hash),
    UNIQUE KEY uq_assistant_owner_session_purpose (owner_user_id,id,purpose),
    KEY ix_assistant_session_expiry (expires_at),
    CONSTRAINT chk_assistant_purpose CHECK (purpose IN ('PUBLIC_KNOWLEDGE','CONTACT_GRAPH')),
    CONSTRAINT chk_assistant_state CHECK (state IN ('OPEN','CLOSED','EXPIRED')),
    CONSTRAINT chk_assistant_policy CHECK (CHAR_LENGTH(policy_version) BETWEEN 1 AND 64),
    CONSTRAINT chk_assistant_version CHECK (version>=0 AND accepted_questions BETWEEN 0 AND 4),
    CONSTRAINT chk_assistant_times CHECK (last_activity_at>=created_at AND expires_at>created_at
        AND expires_at<=DATE_ADD(created_at,INTERVAL 900 SECOND)),
    CONSTRAINT chk_assistant_open_context CHECK (
        (state='OPEN' AND encrypted_context IS NOT NULL AND OCTET_LENGTH(encrypted_context) BETWEEN 29 AND 49180
            AND last_activity_at<expires_at
            AND expires_at=LEAST(DATE_ADD(created_at,INTERVAL 900 SECOND),DATE_ADD(last_activity_at,INTERVAL 300 SECOND)))
        OR (state<>'OPEN' AND encrypted_context IS NULL)),
    CONSTRAINT chk_assistant_pending CHECK (
        (pending_request_id IS NULL AND lease_token IS NULL AND pending_started_at IS NULL AND pending_deadline IS NULL)
        OR (state='OPEN' AND accepted_questions>=1 AND pending_request_id IS NOT NULL AND lease_token IS NOT NULL
            AND pending_started_at IS NOT NULL AND pending_deadline IS NOT NULL
            AND pending_started_at>=created_at AND pending_started_at<=last_activity_at
            AND pending_deadline>pending_started_at
            AND pending_deadline<=DATE_ADD(pending_started_at,INTERVAL 8 SECOND) AND pending_deadline<=expires_at))
) ENGINE=InnoDB;

CREATE TABLE assistant_turn_request (
    id BINARY(16) NOT NULL PRIMARY KEY,
    owner_user_id BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    purpose VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key_hash BINARY(32) NOT NULL,
    request_digest BINARY(32) NOT NULL,
    admitted_version BIGINT NOT NULL,
    lease_token BINARY(16) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT NULL,
    encrypted_result MEDIUMBLOB NULL,
    created_at DATETIME(3) NOT NULL,
    deadline DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    UNIQUE KEY uq_assistant_request_key (owner_user_id,session_id,request_key_hash),
    UNIQUE KEY uq_assistant_request_version (owner_user_id,session_id,admitted_version),
    KEY ix_assistant_request_expiry (expires_at),
    KEY ix_assistant_request_deadline (state,deadline),
    CONSTRAINT fk_assistant_request_session FOREIGN KEY (owner_user_id,session_id,purpose)
        REFERENCES assistant_session (owner_user_id,id,purpose),
    CONSTRAINT chk_assistant_request_state CHECK (state IN ('PROCESSING','COMPLETED','EXPIRED','CANCELLED','INVALIDATED')),
    CONSTRAINT chk_assistant_request_version CHECK (admitted_version BETWEEN 1 AND 9223372036854775806),
    CONSTRAINT chk_assistant_request_times CHECK (deadline>created_at
        AND deadline<=DATE_ADD(created_at,INTERVAL 8 SECOND)
        AND expires_at>=deadline AND expires_at<=DATE_ADD(created_at,INTERVAL 900 SECOND)),
    CONSTRAINT chk_assistant_request_result CHECK (
        (state='COMPLETED' AND result_version IS NOT NULL AND result_version=admitted_version+1
            AND encrypted_result IS NOT NULL AND OCTET_LENGTH(encrypted_result) BETWEEN 29 AND 196636)
        OR (state<>'COMPLETED' AND result_version IS NULL AND encrypted_result IS NULL))
) ENGINE=InnoDB;
