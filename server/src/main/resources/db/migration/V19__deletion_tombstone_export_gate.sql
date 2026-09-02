-- M8 第六子批：删除墓碑只有取得可信灾备导出回执后才允许到期清理。
ALTER TABLE deletion_tombstone
    ADD COLUMN disaster_recovery_exported_at DATETIME(3) NULL AFTER replay_until,
    ADD COLUMN export_receipt_hash BINARY(32) NULL AFTER disaster_recovery_exported_at,
    ADD KEY idx_deletion_tombstone_cleanup (
        replay_until, disaster_recovery_exported_at
    ),
    ADD CONSTRAINT chk_deletion_tombstone_export_receipt CHECK (
        (disaster_recovery_exported_at IS NULL AND export_receipt_hash IS NULL)
        OR
        (disaster_recovery_exported_at IS NOT NULL AND export_receipt_hash IS NOT NULL)
    ),
    ADD CONSTRAINT chk_deletion_tombstone_export_time CHECK (
        disaster_recovery_exported_at IS NULL
        OR disaster_recovery_exported_at >= completed_at
    );
