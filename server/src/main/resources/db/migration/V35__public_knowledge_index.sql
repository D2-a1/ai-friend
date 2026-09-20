-- RAG公开知识索引；仅新增表，不修改既有任务、模板或执行链。
-- 此迁移尚未在真实MySQL运行；模拟测试不构成云端迁移验收。
-- 文档可变元数据随版本保存，待发布导入不得改变旧ACTIVE的适用范围。
CREATE TABLE knowledge_document (
    id BINARY(16) NOT NULL,
    source_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_version BIGINT NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_source (source_key),
    CONSTRAINT chk_knowledge_document_status CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_knowledge_document_version CHECK (row_version >= 1 AND (active_version IS NULL OR active_version >= 1)),
    CONSTRAINT chk_knowledge_document_deleted CHECK (
        (status = 'ACTIVE' AND deleted_at IS NULL) OR (status = 'DELETED' AND deleted_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_document_version (
    document_id BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    locale VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    min_app_version INT NOT NULL,
    max_app_version INT NOT NULL,
    original_text MEDIUMTEXT NULL,
    content_digest BINARY(32) NOT NULL,
    chunker_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (document_id, version),
    CONSTRAINT fk_knowledge_version_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT chk_knowledge_version_number CHECK (version >= 1),
    CONSTRAINT chk_knowledge_version_status CHECK (status IN ('BUILDING', 'READY', 'FAILED', 'RETIRED', 'PURGED')),
    CONSTRAINT chk_knowledge_version_range CHECK (min_app_version >= 1 AND max_app_version >= min_app_version),
    -- 保留版本号与job幂等凭据；非墓碑版本不允许NULL原文。
    CONSTRAINT chk_knowledge_version_size CHECK (
        (status='PURGED' AND original_text IS NULL AND title='')
        OR (status<>'PURGED' AND original_text IS NOT NULL AND OCTET_LENGTH(original_text) BETWEEN 1 AND 262144))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_chunk (
    id BINARY(16) NOT NULL,
    document_id BINARY(16) NOT NULL,
    document_version BIGINT NOT NULL,
    ordinal INT NOT NULL,
    chunker_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    heading VARCHAR(200) NOT NULL,
    chunk_text TEXT NOT NULL,
    source_start INT NOT NULL,
    source_end INT NOT NULL,
    content_digest BINARY(32) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_chunk_ordinal (document_id, document_version, chunker_version, ordinal),
    CONSTRAINT fk_knowledge_chunk_source FOREIGN KEY (document_id, document_version)
        REFERENCES knowledge_document_version(document_id, version),
    CONSTRAINT chk_knowledge_chunk_ordinal CHECK (ordinal BETWEEN 0 AND 1999),
    CONSTRAINT chk_knowledge_chunk_range CHECK (source_start >= 0 AND source_end > source_start AND source_end <= 262144),
    CONSTRAINT chk_knowledge_chunk_size CHECK (
        CHAR_LENGTH(chunk_text) BETWEEN 1 AND 600 AND OCTET_LENGTH(chunk_text) <= 2400
        AND CHAR_LENGTH(chunk_text) = source_end - source_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_index_generation (
    id BINARY(16) NOT NULL,
    build_job_id BINARY(16) NULL,
    build_lease_token BINARY(16) NULL,
    origin VARCHAR(16) NOT NULL DEFAULT 'IMPORT',
    -- 审计来源而非外键；旧世代可回收，删除派生不得挂在被删原文的导入job上。
    source_generation_id BINARY(16) NULL,
    corpus_revision BIGINT NOT NULL,
    profile_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dimension INT NULL,
    tokenizer_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunker_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    corpus_digest BINARY(32) NOT NULL,
    document_count INT NOT NULL,
    chunk_count INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_generation_attempt (build_job_id, build_lease_token),
    CONSTRAINT chk_knowledge_generation_origin CHECK (
        (origin='IMPORT' AND build_job_id IS NOT NULL AND build_lease_token IS NOT NULL AND source_generation_id IS NULL)
        OR (origin='DELETION' AND build_job_id IS NULL AND build_lease_token IS NULL AND source_generation_id IS NOT NULL
            AND source_generation_id<>id)),
    CONSTRAINT chk_knowledge_generation_revision CHECK (corpus_revision >= 0),
    CONSTRAINT chk_knowledge_generation_profile CHECK (
        (profile_id IS NULL AND dimension IS NULL) OR (profile_id IS NOT NULL AND dimension IS NOT NULL AND dimension BETWEEN 1 AND 4096)),
    CONSTRAINT chk_knowledge_generation_counts CHECK (document_count BETWEEN 0 AND 100 AND chunk_count BETWEEN 0 AND 2000),
    CONSTRAINT chk_knowledge_generation_status CHECK (status IN ('BUILDING', 'READY', 'ACTIVE', 'RETIRED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_generation_chunk (
    generation_id BINARY(16) NOT NULL,
    chunk_id BINARY(16) NOT NULL,
    PRIMARY KEY (generation_id, chunk_id),
    CONSTRAINT fk_knowledge_manifest_generation FOREIGN KEY (generation_id) REFERENCES knowledge_index_generation(id),
    CONSTRAINT fk_knowledge_manifest_chunk FOREIGN KEY (chunk_id) REFERENCES knowledge_chunk(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_embedding (
    generation_id BINARY(16) NOT NULL,
    chunk_id BINARY(16) NOT NULL,
    dimension INT NOT NULL,
    vector_blob BLOB NOT NULL,
    digest BINARY(32) NOT NULL,
    PRIMARY KEY (generation_id, chunk_id),
    CONSTRAINT fk_knowledge_embedding_manifest FOREIGN KEY (generation_id, chunk_id)
        REFERENCES knowledge_generation_chunk(generation_id, chunk_id),
    CONSTRAINT chk_knowledge_embedding_size CHECK (dimension BETWEEN 1 AND 4096 AND OCTET_LENGTH(vector_blob) = dimension * 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE knowledge_import_job (
    id BINARY(16) NOT NULL,
    document_id BINARY(16) NOT NULL,
    document_version BIGINT NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_digest BINARY(32) NOT NULL,
    profile_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dimension INT NULL,
    tokenizer_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunker_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    version BIGINT NOT NULL,
    lease_token BINARY(16) NULL,
    lease_until DATETIME(3) NULL,
    next_attempt_at DATETIME(3) NOT NULL,
    deadline DATETIME(3) NOT NULL,
    error_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_import_key (idempotency_key_hash),
    KEY ix_knowledge_import_pending (status, next_attempt_at),
    CONSTRAINT fk_knowledge_import_version FOREIGN KEY (document_id, document_version)
        REFERENCES knowledge_document_version(document_id, version),
    CONSTRAINT chk_knowledge_import_profile CHECK (
        (profile_id IS NULL AND dimension IS NULL) OR (profile_id IS NOT NULL AND dimension IS NOT NULL AND dimension BETWEEN 1 AND 4096)),
    CONSTRAINT chk_knowledge_import_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT chk_knowledge_import_attempts CHECK (attempts BETWEEN 0 AND 3 AND version >= 1
        AND (status <> 'PENDING' OR attempts < 3) AND (status NOT IN ('PROCESSING', 'READY') OR attempts >= 1)),
    CONSTRAINT chk_knowledge_import_error CHECK (error_code IN ('NONE', 'TEMPORARY', 'CONFIGURATION', 'INDEX_INVALID',
        'SOURCE_CHANGED', 'LEASE_EXPIRED', 'ATTEMPTS_EXHAUSTED', 'DEADLINE', 'RESOURCE_LIMIT')
        AND (status <> 'READY' OR error_code = 'NONE') AND (status <> 'FAILED' OR error_code <> 'NONE')),
    CONSTRAINT chk_knowledge_import_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND lease_until <= deadline)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE knowledge_index_generation ADD CONSTRAINT fk_knowledge_generation_job
    FOREIGN KEY (build_job_id) REFERENCES knowledge_import_job(id);

CREATE TABLE knowledge_index_control (
    singleton_id TINYINT NOT NULL,
    active_generation_id BINARY(16) NULL,
    corpus_revision BIGINT NOT NULL,
    version BIGINT NOT NULL,
    lease_job_id BINARY(16) NULL,
    lease_token BINARY(16) NULL,
    lease_until DATETIME(3) NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT fk_knowledge_control_generation FOREIGN KEY (active_generation_id) REFERENCES knowledge_index_generation(id),
    CONSTRAINT fk_knowledge_control_job FOREIGN KEY (lease_job_id) REFERENCES knowledge_import_job(id),
    CONSTRAINT chk_knowledge_control_singleton CHECK (singleton_id = 1 AND corpus_revision >= 0 AND version >= 1),
    CONSTRAINT chk_knowledge_control_lease CHECK (
        (lease_job_id IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR (lease_job_id IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO knowledge_index_control (singleton_id, active_generation_id, corpus_revision, version)
VALUES (1, NULL, 0, 1);
