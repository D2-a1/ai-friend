-- 第四批开发：受限邀请会话鉴权与明确拒绝邀请。
-- 只保存幂等键 SHA-256 摘要，Cookie、CSRF 与幂等键明文均不得落库。

ALTER TABLE invitation_session
    ADD COLUMN decline_idempotency_key_hash BINARY(32) NULL AFTER oauth_state_hash;
