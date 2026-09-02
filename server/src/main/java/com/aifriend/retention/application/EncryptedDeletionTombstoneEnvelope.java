package com.aifriend.retention.application;

import java.util.Arrays;

/**
 * 使用灾备专用密钥生成的版本化删除墓碑密文包。
 *
 * @param keyId 加密密钥编号
 * @param encryptedEnvelope 包含版本、密钥编号、随机 IV 与 AES-GCM 密文的完整信封
 * @param envelopeHash 完整信封 SHA-256
 * @author Codex
 * @since 1.0.0
 */
public record EncryptedDeletionTombstoneEnvelope(
        String keyId,
        byte[] encryptedEnvelope,
        byte[] envelopeHash) {

    /**
     * 校验密文包边界并隔离可变数组。
     */
    public EncryptedDeletionTombstoneEnvelope {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("灾备导出密钥编号无效");
        }
        if (encryptedEnvelope == null
                || encryptedEnvelope.length < 64
                || encryptedEnvelope.length > 2048) {
            throw new IllegalArgumentException("灾备墓碑密文包大小无效");
        }
        if (envelopeHash == null || envelopeHash.length != 32) {
            throw new IllegalArgumentException("灾备墓碑密文包摘要无效");
        }
        encryptedEnvelope = Arrays.copyOf(encryptedEnvelope, encryptedEnvelope.length);
        envelopeHash = Arrays.copyOf(envelopeHash, envelopeHash.length);
    }

    /**
     * 返回完整密文信封的防御性副本。
     *
     * @return 密文包副本
     */
    @Override
    public byte[] encryptedEnvelope() {
        return Arrays.copyOf(encryptedEnvelope, encryptedEnvelope.length);
    }

    /**
     * 返回完整信封摘要的防御性副本。
     *
     * @return 密文包摘要副本
     */
    @Override
    public byte[] envelopeHash() {
        return Arrays.copyOf(envelopeHash, envelopeHash.length);
    }
}
