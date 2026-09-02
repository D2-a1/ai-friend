-- M8 第七子批：可信灾备导出包固定、幂等重试与回执原子确认事实。
ALTER TABLE deletion_tombstone
    ADD COLUMN export_key_id VARCHAR(64) NULL AFTER export_receipt_hash,
    ADD COLUMN export_payload_cipher VARBINARY(2048) NULL AFTER export_key_id,
    ADD COLUMN export_payload_hash BINARY(32) NULL AFTER export_payload_cipher,
    ADD COLUMN export_retry_count INT NOT NULL DEFAULT 0 AFTER export_payload_hash,
    ADD COLUMN export_next_attempt_at DATETIME(3) NULL AFTER export_retry_count,
    ADD COLUMN export_last_attempt_at DATETIME(3) NULL AFTER export_next_attempt_at;

UPDATE deletion_tombstone
SET export_next_attempt_at = created_at
WHERE export_next_attempt_at IS NULL;

ALTER TABLE deletion_tombstone
    MODIFY COLUMN export_next_attempt_at DATETIME(3) NOT NULL,
    ADD KEY idx_deletion_tombstone_export_ready (
        disaster_recovery_exported_at, export_next_attempt_at
    ),
    ADD CONSTRAINT chk_deletion_tombstone_export_retry CHECK (
        export_retry_count >= 0
    ),
    ADD CONSTRAINT chk_deletion_tombstone_export_package CHECK (
        (
            export_key_id IS NULL
            AND export_payload_cipher IS NULL
            AND export_payload_hash IS NULL
            AND disaster_recovery_exported_at IS NULL
        )
        OR
        (
            export_key_id IS NOT NULL
            AND export_payload_hash IS NOT NULL
            AND (
                (
                    export_payload_cipher IS NOT NULL
                    AND disaster_recovery_exported_at IS NULL
                )
                OR
                (
                    export_payload_cipher IS NULL
                    AND disaster_recovery_exported_at IS NOT NULL
                )
            )
        )
    );
