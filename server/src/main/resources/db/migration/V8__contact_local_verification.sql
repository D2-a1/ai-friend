-- 第七批开发：本机微信联系人验证、失败关闭规则版本与幂等重放。
-- 稳定定位只保存服务端 AES-GCM 密文和域隔离 HMAC；请求明文不得进入日志。

ALTER TABLE contact_binding
    MODIFY COLUMN wechat_locator_cipher VARBINARY(4096) NULL,
    ADD COLUMN wechat_version VARCHAR(40) NULL AFTER remark_cipher,
    ADD COLUMN verification_idempotency_key_hash BINARY(32) NULL AFTER local_verification_version,
    ADD COLUMN verification_request_hash BINARY(32) NULL AFTER verification_idempotency_key_hash;

ALTER TABLE contact_binding
    DROP INDEX idx_contact_binding_locator_hash,
    ADD UNIQUE KEY uk_contact_binding_owner_locator (owner_user_id, wechat_locator_hash);
