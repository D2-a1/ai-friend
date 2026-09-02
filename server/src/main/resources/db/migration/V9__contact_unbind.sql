-- 第八批开发：联系人解绑幂等与敏感定位清理。
-- 解绑后仅保留全生命周期唯一性所需的不可逆主体 HMAC。

ALTER TABLE contact_binding
    MODIFY COLUMN contact_subject_cipher VARBINARY(512) NULL,
    ADD COLUMN unbind_idempotency_key_hash BINARY(32) NULL AFTER verification_request_hash,
    ADD COLUMN unbind_request_hash BINARY(32) NULL AFTER unbind_idempotency_key_hash;
