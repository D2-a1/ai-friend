-- 测试样本训练授权：采集与训练分别同意，默认不具备训练资格。
-- 只保存 owner、样本、决定、政策版本和幂等摘要，不保存音频、转写或训练标签。

ALTER TABLE consent_record DROP CHECK chk_consent_type;
ALTER TABLE consent_record ADD CONSTRAINT chk_consent_type CHECK (
    type IN ('BASIC_IDENTITY', 'MICROPHONE', 'NOTIFICATION', 'ACCESSIBILITY',
             'VOICE_TEMPLATE', 'TASK_AUDIO', 'TEST_VOICE_COLLECTION',
             'VOICE_MODEL_TRAINING')
);

ALTER TABLE voice_collection_sample
    DROP CHECK chk_voice_collection_training_disabled;
ALTER TABLE voice_collection_sample
    ADD CONSTRAINT chk_voice_collection_training_flag CHECK (
        training_eligible IN (FALSE, TRUE)
    );
CREATE INDEX idx_voice_collection_training_selection
    ON voice_collection_sample (
        owner_user_id, training_eligible, status, retention_until
    );

CREATE TABLE voice_collection_training_authorization (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    sample_id BINARY(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    policy_version VARCHAR(60) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    resulting_training_eligible BOOLEAN NOT NULL,
    resulting_sample_version BIGINT NOT NULL,
    decided_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_voice_collection_training_idempotency (
        owner_user_id, idempotency_key_hash
    ),
    KEY idx_voice_collection_training_sample (
        owner_user_id, sample_id, decided_at
    ),
    CONSTRAINT fk_voice_collection_training_owner FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id),
    CONSTRAINT fk_voice_collection_training_sample FOREIGN KEY (sample_id)
        REFERENCES voice_collection_sample (id) ON DELETE CASCADE,
    CONSTRAINT chk_voice_collection_training_decision CHECK (
        decision IN ('GRANTED', 'REVOKED')
    ),
    CONSTRAINT chk_voice_collection_training_result CHECK (
        (decision = 'GRANTED' AND resulting_training_eligible = TRUE)
        OR (decision = 'REVOKED' AND resulting_training_eligible = FALSE)
    ),
    CONSTRAINT chk_voice_collection_training_version CHECK (
        resulting_sample_version >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
