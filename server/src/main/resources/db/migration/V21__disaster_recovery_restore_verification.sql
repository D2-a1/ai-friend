-- M8 第八子批：灾备墓碑全量重放核验的最小审计事实。
CREATE TABLE disaster_recovery_restore_verification (
    id BINARY(16) NOT NULL,
    snapshot_id_hash BINARY(32) NOT NULL,
    manifest_hash BINARY(32) NOT NULL,
    expected_item_count BIGINT NOT NULL,
    replayed_item_count BIGINT NOT NULL,
    source_proof_hash BINARY(32) NOT NULL,
    verified_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_disaster_recovery_restore_snapshot (snapshot_id_hash),
    KEY idx_disaster_recovery_restore_verified_at (verified_at),
    CONSTRAINT chk_disaster_recovery_restore_expected CHECK (
        expected_item_count >= 0 AND expected_item_count <= 1000000
    ),
    CONSTRAINT chk_disaster_recovery_restore_replayed CHECK (
        replayed_item_count = expected_item_count
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
