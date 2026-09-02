-- M8 第五子批：注销墓碑、账号代次门禁与旧主体唯一键释放。
CREATE TABLE deletion_tombstone (
    id BINARY(16) NOT NULL,
    subject_hash BINARY(32) NOT NULL,
    old_account_generation BIGINT NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    re_registration_not_before DATETIME(3) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    replay_until DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deletion_tombstone_subject_generation (
        subject_hash, old_account_generation
    ),
    KEY idx_deletion_tombstone_replay_until (replay_until),
    CONSTRAINT chk_deletion_tombstone_generation CHECK (
        old_account_generation >= 1
    ),
    CONSTRAINT chk_deletion_tombstone_re_registration CHECK (
        re_registration_not_before >= accepted_at
    ),
    CONSTRAINT chk_deletion_tombstone_replay CHECK (
        replay_until >= completed_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 完成注销后主体摘要只保留在墓碑中，旧账号释放唯一键供新 UUID 注册。
ALTER TABLE app_user
    MODIFY COLUMN wechat_open_id_hash BINARY(32) NULL;
