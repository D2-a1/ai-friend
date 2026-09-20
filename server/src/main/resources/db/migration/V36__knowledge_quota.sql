-- 仅知识问答/公开导入预算，不修改旧任务限流。未在实库执行。
CREATE TABLE knowledge_quota_control (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    last_seen_at DATETIME(3) NOT NULL,
    CONSTRAINT chk_knowledge_quota_singleton CHECK (singleton_id=1)
) ENGINE=InnoDB;
INSERT INTO knowledge_quota_control (singleton_id,last_seen_at) VALUES (1,UTC_TIMESTAMP(3));

CREATE TABLE knowledge_quota_bucket (
    scope_hash BINARY(32) NOT NULL,
    owner_id BINARY(16) NULL,
    window_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_start DATETIME(3) NOT NULL,
    used_count BIGINT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (scope_hash,window_kind,window_start),
    KEY ix_knowledge_quota_bucket_expiry (expires_at),
    KEY ix_knowledge_quota_bucket_owner (owner_id),
    CONSTRAINT chk_knowledge_quota_window CHECK (window_kind IN ('MINUTE','HOUR','DAY')),
    CONSTRAINT chk_knowledge_quota_count CHECK (used_count BETWEEN 0 AND 1000000),
    CONSTRAINT chk_knowledge_quota_owner CHECK ((window_kind='MINUTE' AND owner_id IS NOT NULL)
        OR (window_kind<>'MINUTE' AND owner_id IS NULL))
) ENGINE=InnoDB;

CREATE TABLE knowledge_quota_reservation (
    operation_id BINARY(16) NOT NULL,
    phase VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt INT NOT NULL,
    sequence_no INT NOT NULL,
    owner_id BINARY(16) NULL,
    profile_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest BINARY(32) NOT NULL,
    decision VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deadline DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (operation_id,phase,attempt,sequence_no),
    KEY ix_knowledge_quota_reservation_expiry (expires_at),
    KEY ix_knowledge_quota_reservation_owner (owner_id),
    CONSTRAINT chk_knowledge_quota_phase CHECK (phase IN ('REQUEST','QUERY_EMBEDDING','ANSWER_GENERATION','IMPORT_EMBEDDING')),
    CONSTRAINT chk_knowledge_quota_decision CHECK (decision IN ('GRANTED','LIMIT_EXCEEDED')),
    CONSTRAINT chk_knowledge_quota_attempt CHECK (attempt>=1 AND
        ((phase='IMPORT_EMBEDDING' AND attempt<=3 AND sequence_no BETWEEN 0 AND 1999 AND owner_id IS NULL)
        OR (phase='ANSWER_GENERATION' AND attempt<=2 AND sequence_no=0 AND owner_id IS NOT NULL)
        OR (phase IN ('REQUEST','QUERY_EMBEDDING') AND attempt=1 AND sequence_no=0 AND owner_id IS NOT NULL))),
    CONSTRAINT chk_knowledge_quota_local CHECK (phase<>'REQUEST' OR profile_id='LOCAL')
) ENGINE=InnoDB;
