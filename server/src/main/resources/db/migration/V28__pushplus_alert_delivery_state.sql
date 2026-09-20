-- R5 第一子批：PushPlus 异步受理流水号与最终送达状态分离。
ALTER TABLE account_closure_alert_delivery
    DROP CHECK chk_account_closure_alert_status,
    DROP CHECK chk_account_closure_alert_receipt,
    ADD COLUMN provider_reference_cipher VARBINARY(512) NULL
        AFTER receipt_hash,
    ADD CONSTRAINT chk_account_closure_alert_status CHECK (
        status IN ('PENDING', 'SUBMITTED', 'DELIVERED')
    ),
    ADD CONSTRAINT chk_account_closure_alert_receipt CHECK (
        (
            status = 'PENDING'
            AND provider_reference_cipher IS NULL
            AND delivered_at IS NULL
            AND receipt_hash IS NULL
        )
        OR
        (
            status = 'SUBMITTED'
            AND provider_reference_cipher IS NOT NULL
            AND delivered_at IS NULL
            AND receipt_hash IS NULL
        )
        OR
        (
            status = 'DELIVERED'
            AND provider_reference_cipher IS NULL
            AND delivered_at IS NOT NULL
            AND receipt_hash IS NOT NULL
        )
    );