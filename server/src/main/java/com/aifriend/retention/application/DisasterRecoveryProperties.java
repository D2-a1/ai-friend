package com.aifriend.retention.application;

import java.util.Arrays;
import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 灾备恢复启动门禁配置。
 *
 * @param restoreMode 当前实例是否正在从灾备介质恢复
 * @param restoreSnapshotId 本次恢复必须绑定的可信快照编号
 * @param exportEnabled 是否允许向可信独立介质导出删除墓碑
 * @param exportKeyId 灾备导出 AES 密钥编号，不是秘密
 * @param exportEncryptionKeyBase64 灾备导出专用 256 位 AES 密钥 Base64
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.retention.disaster-recovery")
public record DisasterRecoveryProperties(
        boolean restoreMode,
        String restoreSnapshotId,
        boolean exportEnabled,
        String exportKeyId,
        String exportEncryptionKeyBase64) {

    private static final String KEY_ID_PATTERN = "[A-Za-z0-9._-]{1,64}";
    private static final String SNAPSHOT_ID_PATTERN = "[A-Za-z0-9._:-]{8,128}";

    /**
     * 校验启用导出时必须具备独立且格式正确的专用密钥。
     */
    public DisasterRecoveryProperties {
        restoreSnapshotId = restoreSnapshotId == null ? "" : restoreSnapshotId;
        exportKeyId = exportKeyId == null ? "" : exportKeyId;
        exportEncryptionKeyBase64 = exportEncryptionKeyBase64 == null
                ? "" : exportEncryptionKeyBase64;
        if (restoreMode && !restoreSnapshotId.matches(SNAPSHOT_ID_PATTERN)) {
            throw new IllegalArgumentException("灾备恢复快照编号无效");
        }
        if (exportEnabled || restoreMode) {
            byte[] decodedKey = decodeKey(exportEncryptionKeyBase64);
            try {
                if (!exportKeyId.matches(KEY_ID_PATTERN) || decodedKey.length != 32) {
                    throw new IllegalArgumentException("灾备信封密钥配置无效");
                }
            } finally {
                Arrays.fill(decodedKey, (byte) 0);
            }
        }
    }

    /**
     * 取得当前恢复必须绑定的快照编号。
     *
     * @return 恢复快照编号
     * @throws IllegalStateException 当前实例不是恢复模式时抛出
     */
    public String requireRestoreSnapshotId() {
        if (!restoreMode) {
            throw new IllegalStateException("当前实例不是灾备恢复模式");
        }
        return restoreSnapshotId;
    }

    /**
     * 取得灾备导出专用 AES 密钥原始字节副本。
     *
     * @return 32 字节密钥副本
     * @throws IllegalStateException 灾备导出未启用时抛出
     */
    public byte[] requireExportEncryptionKey() {
        if (!exportEnabled) {
            throw new IllegalStateException("灾备墓碑导出未启用");
        }
        return decodeKey(exportEncryptionKeyBase64);
    }

    /**
     * 取得恢复信封解密所需的灾备专用 AES 密钥。
     *
     * @return 32 字节密钥副本
     * @throws IllegalStateException 当前实例不是恢复模式时抛出
     */
    public byte[] requireRestoreEncryptionKey() {
        if (!restoreMode) {
            throw new IllegalStateException("当前实例不是灾备恢复模式");
        }
        return decodeKey(exportEncryptionKeyBase64);
    }

    /**
     * 返回不包含密钥内容的配置描述。
     *
     * @return 脱敏配置描述
     */
    @Override
    public String toString() {
        return "DisasterRecoveryProperties[restoreMode=" + restoreMode
                + ", restoreSnapshotId=***"
                + ", exportEnabled=" + exportEnabled
                + ", exportKeyId=" + exportKeyId
                + ", exportEncryptionKeyBase64=***]";
    }

    private static byte[] decodeKey(String encodedKey) {
        try {
            return Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }
}
