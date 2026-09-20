-- 私人关系投影：只保存权威引用，不存称呼、定位、音素或模板。尚未在实库执行。
CREATE TABLE knowledge_graph_snapshot (
    owner_user_id BINARY(16) NOT NULL PRIMARY KEY,
    generation BINARY(16) NOT NULL,
    source_digest BINARY(32) NOT NULL,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    node_count INT NOT NULL,
    edge_count INT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uq_graph_owner_generation (owner_user_id,generation),
    CONSTRAINT chk_graph_status CHECK (status='ACTIVE'),
    CONSTRAINT chk_graph_version CHECK (version>=1),
    CONSTRAINT chk_graph_counts CHECK (node_count BETWEEN 1 AND 200 AND edge_count BETWEEN 0 AND 500)
) ENGINE=InnoDB;

CREATE TABLE knowledge_graph_node (
    owner_user_id BINARY(16) NOT NULL,
    generation BINARY(16) NOT NULL,
    node_id BINARY(16) NOT NULL,
    node_type VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id BINARY(16) NOT NULL,
    source_version BIGINT NOT NULL,
    PRIMARY KEY (owner_user_id,generation,node_id),
    UNIQUE KEY uq_graph_node_source (owner_user_id,generation,node_type,source_id),
    CONSTRAINT fk_graph_node_snapshot FOREIGN KEY (owner_user_id,generation)
        REFERENCES knowledge_graph_snapshot (owner_user_id,generation),
    CONSTRAINT chk_graph_node_type CHECK (node_type IN ('USER','CONTACT','ALIAS')),
    CONSTRAINT chk_graph_source_version CHECK (source_version>=0),
    CONSTRAINT chk_graph_user_source CHECK (node_type<>'USER' OR source_id=owner_user_id)
) ENGINE=InnoDB;

CREATE TABLE knowledge_graph_edge (
    owner_user_id BINARY(16) NOT NULL,
    generation BINARY(16) NOT NULL,
    edge_id BINARY(16) NOT NULL,
    from_id BINARY(16) NOT NULL,
    to_id BINARY(16) NOT NULL,
    relation_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (owner_user_id,generation,edge_id),
    UNIQUE KEY uq_graph_one_parent (owner_user_id,generation,to_id),
    CONSTRAINT fk_graph_edge_from FOREIGN KEY (owner_user_id,generation,from_id)
        REFERENCES knowledge_graph_node (owner_user_id,generation,node_id),
    CONSTRAINT fk_graph_edge_to FOREIGN KEY (owner_user_id,generation,to_id)
        REFERENCES knowledge_graph_node (owner_user_id,generation,node_id),
    CONSTRAINT chk_graph_edge_type CHECK (relation_type IN ('HAS_CONTACT','HAS_ALIAS')),
    CONSTRAINT chk_graph_edge_no_self CHECK (from_id<>to_id)
) ENGINE=InnoDB;
