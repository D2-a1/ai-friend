-- M8 第三子批：账号注销物理清理重试、24/48 小时告警与 15 分钟升级事实。
ALTER TABLE account_closure_request
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER completed_at,
    ADD COLUMN next_attempt_at DATETIME(3) NULL AFTER retry_count,
    ADD COLUMN last_attempt_at DATETIME(3) NULL AFTER next_attempt_at,
    ADD COLUMN warning_24h_at DATETIME(3) NULL AFTER last_attempt_at,
    ADD COLUMN p0_opened_at DATETIME(3) NULL AFTER warning_24h_at,
    ADD COLUMN acknowledgement_due_at DATETIME(3) NULL AFTER p0_opened_at,
    ADD COLUMN acknowledged_at DATETIME(3) NULL AFTER acknowledgement_due_at,
    ADD COLUMN escalated_at DATETIME(3) NULL AFTER acknowledged_at,
    ADD COLUMN deadline_breached_at DATETIME(3) NULL AFTER escalated_at;

UPDATE account_closure_request
SET next_attempt_at = accepted_at
WHERE next_attempt_at IS NULL;

ALTER TABLE account_closure_request
    MODIFY COLUMN next_attempt_at DATETIME(3) NOT NULL,
    ADD KEY idx_account_closure_ready (status, next_attempt_at),
    ADD KEY idx_account_closure_alerts (
        status, accepted_at, p0_opened_at, escalated_at, deadline_breached_at
    ),
    ADD CONSTRAINT chk_account_closure_retry CHECK (retry_count >= 0),
    ADD CONSTRAINT chk_account_closure_ack_due CHECK (
        acknowledgement_due_at IS NULL OR p0_opened_at IS NOT NULL
    );

-- 完成在线清理时清空可解密微信身份材料，仅保留重新注册门禁所需不可逆摘要。
ALTER TABLE app_user
    MODIFY COLUMN wechat_open_id_cipher VARBINARY(512) NULL;
