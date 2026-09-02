-- 测试语音人工复核：加密保存已确认文字，未复核样本不得进入未来训练选择。
-- 既有样本无法在没有安全回放能力时补做复核，因此保留为 PENDING 并清除训练资格。

ALTER TABLE voice_collection_sample
    ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        AFTER dialect_code,
    ADD COLUMN reviewed_transcript_cipher VARBINARY(1024) NULL
        AFTER review_status,
    ADD COLUMN review_policy_version VARCHAR(60) NULL
        AFTER reviewed_transcript_cipher,
    ADD COLUMN reviewed_at DATETIME(3) NULL
        AFTER review_policy_version;

UPDATE voice_collection_sample
SET training_eligible = FALSE,
    version = version + 1,
    updated_at = UTC_TIMESTAMP(3)
WHERE training_eligible = TRUE;

DROP INDEX idx_voice_collection_training_selection ON voice_collection_sample;
CREATE INDEX idx_voice_collection_training_selection
    ON voice_collection_sample (
        owner_user_id, training_eligible, review_status, status, retention_until
    );

ALTER TABLE voice_collection_sample
    ADD CONSTRAINT chk_voice_collection_review_status CHECK (
        review_status IN ('PENDING', 'CONFIRMED')
    ),
    ADD CONSTRAINT chk_voice_collection_review_content CHECK (
        (review_status = 'PENDING'
            AND reviewed_transcript_cipher IS NULL
            AND review_policy_version IS NULL
            AND reviewed_at IS NULL)
        OR
        (review_status = 'CONFIRMED'
            AND reviewed_transcript_cipher IS NOT NULL
            AND review_policy_version = 'voice-sample-review-v1'
            AND reviewed_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_voice_collection_training_review CHECK (
        training_eligible = FALSE OR review_status = 'CONFIRMED'
    );
