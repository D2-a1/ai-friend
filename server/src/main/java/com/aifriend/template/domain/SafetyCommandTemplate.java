package com.aifriend.template.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 不含原始音频或声学模板明文的安全指令模板快照。
 *
 * @param id 模板 UUID
 * @param enrollmentId 整批注册 UUID
 * @param ownerUserId owner UUID
 * @param commandType 安全指令类型
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 声学模板模型版本
 * @param thresholdVersion 分类阈值版本
 * @param templateCipher 声学模板 AES-GCM 密文，替换后为空
 * @param templateDigest 模板 SHA-256 摘要，替换后为空
 * @param status 生命周期状态
 * @param version 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param replacedAt 替换时间，有效时为空
 * @author Codex
 * @since 1.0.0
 */
public record SafetyCommandTemplate(
        UUID id,
        UUID enrollmentId,
        UUID ownerUserId,
        SafetyCommandType commandType,
        String dialectCode,
        String dialectPackageVersion,
        String modelVersion,
        String thresholdVersion,
        byte[] templateCipher,
        byte[] templateDigest,
        SafetyCommandTemplateStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant replacedAt) {

    /** 创建带防御性数组副本的快照。 */
    public SafetyCommandTemplate {
        templateCipher = cloneNullable(templateCipher);
        templateDigest = cloneNullable(templateDigest);
    }

    /**
     * 获取模板密文副本。
     *
     * @return 模板密文副本，可空
     */
    @Override
    public byte[] templateCipher() {
        return cloneNullable(templateCipher);
    }

    /**
     * 获取模板摘要副本。
     *
     * @return 模板摘要副本，可空
     */
    @Override
    public byte[] templateDigest() {
        return cloneNullable(templateDigest);
    }

    /**
     * 立即清空可解密模板材料并进入替换状态。
     *
     * @param now 替换时间
     * @return 仅保留版本元数据的历史快照
     */
    public SafetyCommandTemplate replace(Instant now) {
        return new SafetyCommandTemplate(
                id, enrollmentId, ownerUserId, commandType,
                dialectCode, dialectPackageVersion, modelVersion, thresholdVersion,
                null, null, SafetyCommandTemplateStatus.REPLACED,
                version, createdAt, now, now);
    }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
