-- M8 第九子批：注销告警按接收组可靠投递、幂等重试与回执摘要事实。
CREATE TABLE account_closure_alert_delivery (
    id BINARY(16) NOT NULL,
    outbox_event_id BINARY(16) NOT NULL,
    audience VARCHAR(32) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    acknowledgement_due_at DATETIME(3) NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    last_attempt_at DATETIME(3) NULL,
    delivered_at DATETIME(3) NULL,
    receipt_hash BINARY(32) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_closure_alert_event_audience (
        outbox_event_id, audience
    ),
    KEY idx_account_closure_alert_ready (
        status, next_attempt_at
    ),
    CONSTRAINT chk_account_closure_alert_audience CHECK (
        audience IN ('ON_CALL', 'PRIVACY_OFFICER')
    ),
    CONSTRAINT chk_account_closure_alert_event_type CHECK (
        event_type IN (
            'ACCOUNT_CLOSURE_DELAY_WARNING',
            'ACCOUNT_CLOSURE_P0_OPENED',
            'ACCOUNT_CLOSURE_P0_ESCALATED',
            'ACCOUNT_CLOSURE_DEADLINE_BREACHED'
        )
    ),
    CONSTRAINT chk_account_closure_alert_status CHECK (
        status IN ('PENDING', 'DELIVERED')
    ),
    CONSTRAINT chk_account_closure_alert_retry CHECK (
        retry_count >= 0
    ),
    CONSTRAINT chk_account_closure_alert_ack_due CHECK (
        (
            event_type = 'ACCOUNT_CLOSURE_P0_OPENED'
            AND acknowledgement_due_at IS NOT NULL
        )
        OR
        (
            event_type <> 'ACCOUNT_CLOSURE_P0_OPENED'
            AND acknowledgement_due_at IS NULL
        )
    ),
    CONSTRAINT chk_account_closure_alert_receipt CHECK (
        (
            status = 'PENDING'
            AND delivered_at IS NULL
            AND receipt_hash IS NULL
        )
        OR
        (
            status = 'DELIVERED'
            AND delivered_at IS NOT NULL
            AND receipt_hash IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
