-- 武冈话个人训练数据集版本：只冻结成员和摘要，不复制音频、对象键或人工复核文字。
-- 删除样本时成员随样本删除，后续读取因成员数量或清单摘要不一致而失败关闭。

CREATE TABLE voice_training_dataset (
    id BINARY(16) NOT NULL,
    owner_user_id BINARY(16) NOT NULL,
    dataset_version VARCHAR(60) NOT NULL,
    dataset_policy_version VARCHAR(60) NOT NULL,
    training_policy_version VARCHAR(60) NOT NULL,
    review_policy_version VARCHAR(60) NOT NULL,
    sample_count SMALLINT UNSIGNED NOT NULL,
    manifest_sha256 BINARY(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_voice_training_dataset_version (
        owner_user_id, dataset_version
    ),
    KEY idx_voice_training_dataset_owner_created (
        owner_user_id, created_at
    ),
    CONSTRAINT fk_voice_training_dataset_owner FOREIGN KEY (owner_user_id)
        REFERENCES app_user (id),
    CONSTRAINT chk_voice_training_dataset_count CHECK (
        sample_count BETWEEN 1 AND 500
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE voice_training_dataset_member (
    dataset_id BINARY(16) NOT NULL,
    sample_id BINARY(16) NOT NULL,
    member_order SMALLINT UNSIGNED NOT NULL,
    sample_version BIGINT NOT NULL,
    audio_sha256 BINARY(32) NOT NULL,
    reviewed_transcript_sha256 BINARY(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    prompt_code VARCHAR(64) NOT NULL,
    environment VARCHAR(24) NOT NULL,
    dialect_code VARCHAR(40) NOT NULL,
    PRIMARY KEY (dataset_id, sample_id),
    UNIQUE KEY uk_voice_training_dataset_order (dataset_id, member_order),
    CONSTRAINT fk_voice_training_member_dataset FOREIGN KEY (dataset_id)
        REFERENCES voice_training_dataset (id) ON DELETE CASCADE,
    CONSTRAINT fk_voice_training_member_sample FOREIGN KEY (sample_id)
        REFERENCES voice_collection_sample (id) ON DELETE CASCADE,
    CONSTRAINT chk_voice_training_member_order CHECK (
        member_order BETWEEN 0 AND 499
    ),
    CONSTRAINT chk_voice_training_member_version CHECK (
        sample_version >= 0
    ),
    CONSTRAINT chk_voice_training_member_category CHECK (
        category IN (
            'WAKE_WORD', 'CONTACT_ALIAS', 'SAFETY_COMMAND', 'FULL_TASK', 'NEGATIVE'
        )
    ),
    CONSTRAINT chk_voice_training_member_environment CHECK (
        environment IN ('QUIET', 'HOME_NOISE', 'OUTDOOR')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
