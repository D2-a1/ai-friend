package com.aifriend.retention.application;

/**
 * 删除墓碑规范字节与专用 AES-GCM 信封编解码端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface DeletionTombstoneEnvelopeCodecPort {

    /**
     * 生成版本化加密信封。
     *
     * @param record 最小删除墓碑事实
     * @return 加密信封和摘要
     * @throws RuntimeException 配置、规范化或加密失败时抛出
     */
    EncryptedDeletionTombstoneEnvelope seal(DeletionTombstoneExportRecord record);

    /**
     * 解密并严格解析版本化信封。
     *
     * @param envelope 完整加密信封
     * @return 经校验的最小删除墓碑事实
     * @throws RuntimeException 密钥、摘要、版本、格式或认证标签无效时抛出
     */
    DeletionTombstoneExportRecord open(EncryptedDeletionTombstoneEnvelope envelope);
}
