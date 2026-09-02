-- 第六批开发：微信 OAuth 回调、当前邀请会话与亲友明确接受。
-- OAuth 主体只保存密文/HMAC；code、state、Cookie、CSRF 和幂等键明文不得落库。

ALTER TABLE invitation_session
    ADD COLUMN accept_idempotency_key_hash BINARY(32) NULL AFTER decline_idempotency_key_hash,
    ADD COLUMN oauth_subject_hash BINARY(32) NULL AFTER accept_idempotency_key_hash,
    ADD COLUMN oauth_subject_cipher VARBINARY(512) NULL AFTER oauth_subject_hash,
    ADD COLUMN oauth_verified_at DATETIME(3) NULL AFTER oauth_subject_cipher,
    ADD COLUMN accepted_consent_policy_version VARCHAR(40) NULL AFTER oauth_verified_at;

ALTER TABLE contact_binding
    ADD COLUMN consent_policy_version VARCHAR(40) NULL AFTER relationship,
    ADD COLUMN consented_at DATETIME(3) NULL AFTER consent_policy_version;
