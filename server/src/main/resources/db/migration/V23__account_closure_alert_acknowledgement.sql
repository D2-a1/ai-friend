-- M8 第十子批：注销 P0 告警唯一接手确认、独立身份摘要与迟到升级闭环。
CREATE TABLE account_closure_alert_acknowledgement (
    id BINARY(16) NOT NULL,
    closure_request_id BINARY(16) NOT NULL,
    delivery_id BINARY(16) NOT NULL,
    audience VARCHAR(32) NOT NULL,
    responder_subject_hash BINARY(32) NOT NULL,
    authentication_context_hash BINARY(32) NOT NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    timing VARCHAR(16) NOT NULL,
    acknowledgement_due_at DATETIME(3) NOT NULL,
    acknowledged_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_closure_ack_closure (closure_request_id),
    UNIQUE KEY uk_account_closure_ack_delivery (delivery_id),
    CONSTRAINT fk_account_closure_ack_closure FOREIGN KEY (closure_request_id)
        REFERENCES account_closure_request (id),
    CONSTRAINT fk_account_closure_ack_delivery FOREIGN KEY (delivery_id)
        REFERENCES account_closure_alert_delivery (id),
    CONSTRAINT chk_account_closure_ack_audience CHECK (
        audience IN ('ON_CALL', 'PRIVACY_OFFICER')
    ),
    CONSTRAINT chk_account_closure_ack_timing CHECK (
        timing IN ('TIMELY', 'LATE')
    ),
    CONSTRAINT chk_account_closure_ack_time CHECK (
        (
            timing = 'TIMELY'
            AND acknowledged_at < acknowledgement_due_at
        )
        OR
        (
            timing = 'LATE'
            AND acknowledged_at >= acknowledgement_due_at
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
