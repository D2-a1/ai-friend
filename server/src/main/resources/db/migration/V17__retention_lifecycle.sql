-- M8 第四子批：统一最长 24 小时生命周期扫描、音频失败退避和全局有界索引。
ALTER TABLE audio_object
    ADD COLUMN retention_retry_count INT NOT NULL DEFAULT 0 AFTER deleted_at,
    ADD COLUMN retention_next_attempt_at DATETIME(3) NULL AFTER retention_retry_count,
    ADD COLUMN retention_last_attempt_at DATETIME(3) NULL AFTER retention_next_attempt_at;

UPDATE audio_object
SET retention_next_attempt_at = retention_until
WHERE retention_next_attempt_at IS NULL;

ALTER TABLE audio_object
    ADD KEY idx_audio_object_retention_ready (
        status, retention_until, retention_next_attempt_at
    ),
    ADD CONSTRAINT chk_audio_object_retention_retry CHECK (
        retention_retry_count >= 0
    );

ALTER TABLE task_session
    ADD KEY idx_task_retention_created (created_at);

ALTER TABLE invitation_session
    ADD KEY idx_invitation_session_retention_created (created_at);

ALTER TABLE contact_invitation
    ADD KEY idx_contact_invitation_retention_created (created_at);
