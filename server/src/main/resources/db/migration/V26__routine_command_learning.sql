-- M3 日常指令个性化第一子批：已确认任务的可靠学习 Outbox。
-- 只保存有限动作、可证明声学范围和版本元数据，不保存转写、联系人或消息正文。

CREATE TABLE routine_command_learning_outbox (
    id BINARY(16) NOT NULL,
    task_session_id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    audio_object_id BINARY(16) NOT NULL,
    intent VARCHAR(30) NOT NULL,
    action_start_ms INT NOT NULL,
    action_end_ms INT NOT NULL,
    dialect_code VARCHAR(40) NOT NULL,
    dialect_package_version VARCHAR(60) NOT NULL,
    template_model_version VARCHAR(60) NOT NULL,
    threshold_version VARCHAR(60) NOT NULL,
    namespace_version_at_enqueue BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(3) NOT NULL,
    lease_token BINARY(16) NULL,
    lease_until DATETIME(3) NULL,
    source_retention_until DATETIME(3) NOT NULL,
    last_error_code VARCHAR(40) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_routine_learning_task (task_session_id),
    KEY idx_routine_learning_ready (state, available_at),
    KEY idx_routine_learning_owner (owner_user_id, state),
    CONSTRAINT fk_routine_learning_task
        FOREIGN KEY (task_session_id) REFERENCES task_session (id),
    CONSTRAINT fk_routine_learning_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_routine_learning_audio
        FOREIGN KEY (audio_object_id) REFERENCES audio_object (id),
    CONSTRAINT chk_routine_learning_intent CHECK (
        intent IN ('SEND_MESSAGE', 'VOICE_CALL', 'VIDEO_CALL')
    ),
    CONSTRAINT chk_routine_learning_range CHECK (
        action_start_ms >= 0 AND action_end_ms > action_start_ms
    ),
    CONSTRAINT chk_routine_learning_namespace CHECK (
        namespace_version_at_enqueue >= 1
    ),
    CONSTRAINT chk_routine_learning_state CHECK (
        state IN ('PENDING', 'PROCESSING', 'RETRY', 'DONE', 'SKIPPED')
    ),
    CONSTRAINT chk_routine_learning_attempts CHECK (
        attempts >= 0 AND attempts <= 8
    ),
    CONSTRAINT chk_routine_learning_lease CHECK (
        (state = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
