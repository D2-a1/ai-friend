package com.aifriend.identity.application;

/**
 * 已验证设备的最小身份。
 *
 * @param publicKeySha256 Android Keystore 公钥 SHA-256 摘要；门禁关闭时为空
 * @author Codex
 * @since 1.0.0
 */
public record DeviceAuthentication(byte[] publicKeySha256) {

    /**
     * 创建防御性复制的设备身份。
     */
    public DeviceAuthentication {
        publicKeySha256 = publicKeySha256 == null ? null : publicKeySha256.clone();
    }

    /**
     * 返回防御性复制的设备公钥摘要。
     *
     * @return SHA-256 摘要；门禁关闭时为空
     */
    @Override
    public byte[] publicKeySha256() {
        return publicKeySha256 == null ? null : publicKeySha256.clone();
    }
}
