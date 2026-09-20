package com.aifriend.contact.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 不含称呼、音素提示或声学模板明文的联系人称呼快照。
 *
 * @param id 称呼 UUID
 * @param ownerUserId 老人账号 UUID
 * @param bindingId 联系人绑定 UUID
 * @param displayTextCipher 辅助展示文字密文，删除后为空
 * @param phoneticHintCipher 可选音素提示密文，删除后为空
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 声学模板模型版本
 * @param thresholdVersion 注册冲突阈值版本
 * @param templateCipher 声学内容模板密文，删除后为空
 * @param templateDigest 模板完整性摘要，删除后为空
 * @param status 生命周期状态
 * @param createIdempotencyKeyHash 创建幂等键摘要
 * @param createRequestHash 创建请求语义摘要
 * @param deleteIdempotencyKeyHash 最近删除幂等键摘要，可空
 * @param deleteRequestHash 最近删除请求语义摘要，可空
 * @param version 数据库乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param deletedAt 删除时间，可空
 * @author Codex
 * @since 1.0.0
 */
public record ContactAlias(
        UUID id,
        UUID ownerUserId,
        UUID bindingId,
        byte[] displayTextCipher,
        byte[] phoneticHintCipher,
        String dialectCode,
        String dialectPackageVersion,
        String modelVersion,
        String thresholdVersion,
        byte[] templateCipher,
        byte[] templateDigest,
        ContactAliasStatus status,
        byte[] createIdempotencyKeyHash,
        byte[] createRequestHash,
        byte[] deleteIdempotencyKeyHash,
        byte[] deleteRequestHash,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    /** 创建防止外部修改敏感数组的称呼快照。 */
    public ContactAlias {
        displayTextCipher = cloneNullable(displayTextCipher);
        phoneticHintCipher = cloneNullable(phoneticHintCipher);
        templateCipher = cloneNullable(templateCipher);
        templateDigest = cloneNullable(templateDigest);
        createIdempotencyKeyHash = createIdempotencyKeyHash.clone();
        createRequestHash = createRequestHash.clone();
        deleteIdempotencyKeyHash = cloneNullable(deleteIdempotencyKeyHash);
        deleteRequestHash = cloneNullable(deleteRequestHash);
    }

    /**
     * 获取辅助展示文字密文的防御性副本。
     *
     * @return 辅助展示文字密文副本，可空
     */
    @Override
    public byte[] displayTextCipher() {
        return cloneNullable(displayTextCipher);
    }

    /**
     * 获取音素提示密文的防御性副本。
     *
     * @return 音素提示密文副本，可空
     */
    @Override
    public byte[] phoneticHintCipher() {
        return cloneNullable(phoneticHintCipher);
    }

    /**
     * 获取声学模板密文的防御性副本。
     *
     * @return 声学模板密文副本，可空
     */
    @Override
    public byte[] templateCipher() {
        return cloneNullable(templateCipher);
    }

    /**
     * 获取模板摘要的防御性副本。
     *
     * @return 模板摘要副本，可空
     */
    @Override
    public byte[] templateDigest() {
        return cloneNullable(templateDigest);
    }

    /**
     * 获取创建幂等键摘要的防御性副本。
     *
     * @return 创建幂等键摘要副本
     */
    @Override
    public byte[] createIdempotencyKeyHash() {
        return createIdempotencyKeyHash.clone();
    }

    /**
     * 获取创建请求摘要的防御性副本。
     *
     * @return 创建请求摘要副本
     */
    @Override
    public byte[] createRequestHash() {
        return createRequestHash.clone();
    }

    /**
     * 获取删除幂等键摘要的防御性副本。
     *
     * @return 删除幂等键摘要副本，可空
     */
    @Override
    public byte[] deleteIdempotencyKeyHash() {
        return cloneNullable(deleteIdempotencyKeyHash);
    }

    /**
     * 获取删除请求摘要的防御性副本。
     *
     * @return 删除请求摘要副本，可空
     */
    @Override
    public byte[] deleteRequestHash() {
        return cloneNullable(deleteRequestHash);
    }

    /**
     * 判断有效称呼是否仍具备任务匹配所需的全部持久化材料。
     *
     * @return 展示文字、模板密文和摘要均存在时返回 true
     */
    public boolean hasPersistedMaterial() {
        return displayTextCipher != null && displayTextCipher.length > 0
                && templateCipher != null && templateCipher.length > 0
                && templateDigest != null && templateDigest.length > 0;
    }

    /**
     * 判断称呼的四个声学版本是否与当前已验签方言包一致。
     *
     * @param expectedDialectCode 方言代码
     * @param expectedPackageVersion 方言包版本
     * @param expectedModelVersion 模型版本
     * @param expectedThresholdVersion 阈值版本
     * @return 四个版本全部一致时返回 true
     */
    public boolean matchesVersions(
            String expectedDialectCode,
            String expectedPackageVersion,
            String expectedModelVersion,
            String expectedThresholdVersion) {
        return dialectCode.equals(expectedDialectCode)
                && dialectPackageVersion.equals(expectedPackageVersion)
                && modelVersion.equals(expectedModelVersion)
                && thresholdVersion.equals(expectedThresholdVersion);
    }

    /**
     * 立即删除可解密展示信息和声学模板，并保留最小幂等墓碑。
     *
     * @param idempotencyKeyHash 删除幂等键摘要
     * @param requestHash 删除请求语义摘要
     * @param now 删除时间
     * @return 已删除称呼快照
     */
    public ContactAlias delete(
            byte[] idempotencyKeyHash,
            byte[] requestHash,
            Instant now) {
        return new ContactAlias(
                id, ownerUserId, bindingId, null, null,
                dialectCode, dialectPackageVersion, modelVersion, thresholdVersion,
                null, null, ContactAliasStatus.DELETED,
                createIdempotencyKeyHash, createRequestHash,
                idempotencyKeyHash, requestHash, version, createdAt, now, now);
    }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
