-- M3 任务语音链：owner 隔离任务、候选、写操作幂等记录。
-- 转写、复述、候选展示和渠道结果只进入 AES-GCM 加密载荷，禁止明文落库。

CREATE TABLE task_namespace (
    owner_user_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_user_id),
    CONSTRAINT fk_task_namespace_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_session (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    source_audio_object_id BINARY(16) NOT NULL,
    client_task_id VARCHAR(64) NOT NULL,
    create_idempotency_key_hash BINARY(32) NOT NULL,
    create_request_hash BINARY(32) NOT NULL,
    state VARCHAR(30) NOT NULL,
    payload_cipher MEDIUMBLOB NOT NULL,
    selected_contact_id BINARY(16) NULL,
    summary_hash VARCHAR(100) NULL,
    plan_id VARCHAR(64) NULL,
    plan_expires_at DATETIME(3) NULL,
    session_version BIGINT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_owner_create_key (
        owner_user_id, create_idempotency_key_hash
    ),
    UNIQUE KEY uk_task_owner_client_task (owner_user_id, client_task_id),
    KEY idx_task_owner_updated (owner_user_id, updated_at),
    KEY idx_task_expires (expires_at),
    CONSTRAINT fk_task_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_task_audio
        FOREIGN KEY (source_audio_object_id) REFERENCES audio_object (id),
    CONSTRAINT fk_task_selected_contact
        FOREIGN KEY (selected_contact_id) REFERENCES contact_binding (id),
    CONSTRAINT chk_task_state CHECK (state IN (
        'CREATED', 'PROCESSING', 'AWAITING_SELECTION',
        'AWAITING_CONFIRMATION', 'NEEDS_CONTENT_REPEAT', 'NEEDS_RETRY',
        'EXECUTING', 'REJECTED', 'CANCELLED', 'COMPLETED', 'PARTIAL',
        'FAILED'
    )),
    CONSTRAINT chk_task_version CHECK (session_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_candidate (
    id BINARY(16) NOT NULL,
    task_session_id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    contact_id BINARY(16) NOT NULL,
    score_band VARCHAR(20) NOT NULL,
    rank_no INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_candidate_session_rank (task_session_id, rank_no),
    UNIQUE KEY uk_task_candidate_session_contact (task_session_id, contact_id),
    KEY idx_task_candidate_owner_session (owner_user_id, task_session_id),
    CONSTRAINT fk_task_candidate_session
        FOREIGN KEY (task_session_id) REFERENCES task_session (id),
    CONSTRAINT fk_task_candidate_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_task_candidate_contact
        FOREIGN KEY (contact_id) REFERENCES contact_binding (id),
    CONSTRAINT chk_task_candidate_score CHECK (
        score_band IN ('UNIQUE', 'AMBIGUOUS')
    ),
    CONSTRAINT chk_task_candidate_rank CHECK (rank_no BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_operation (
    id BINARY(16) NOT NULL,
    task_session_id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    resulting_session_version BIGINT NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_operation_owner_type_key (
        owner_user_id, operation_type, idempotency_key_hash
    ),
    KEY idx_task_operation_session (task_session_id),
    CONSTRAINT fk_task_operation_session
        FOREIGN KEY (task_session_id) REFERENCES task_session (id),
    CONSTRAINT fk_task_operation_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_task_operation_type CHECK (
        operation_type IN ('SELECTION', 'CONFIRMATION', 'CHANNEL_RESULT')
    ),
    CONSTRAINT chk_task_operation_version CHECK (resulting_session_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
