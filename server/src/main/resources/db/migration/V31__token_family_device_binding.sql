-- 自用设备白名单：token family 只绑定 Android Keystore 公钥 SHA-256 摘要。
-- 既有 family 保持 NULL；设备门禁开启后，缺少设备摘要的旧 JWT/refresh token 失败关闭。

ALTER TABLE token_family
    ADD COLUMN device_public_key_sha256 BINARY(32) NULL AFTER user_id,
    ADD KEY idx_token_family_device_status (device_public_key_sha256, status);
