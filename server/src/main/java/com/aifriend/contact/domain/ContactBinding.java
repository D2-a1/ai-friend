package com.aifriend.contact.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 不暴露微信主体或定位明文的联系人绑定快照。
 *
 * @param id 绑定 UUID
 * @param ownerUserId 老人账号 UUID
 * @param contactSubjectHash 亲友微信主体 HMAC 查询键
 * @param contactSubjectCipher 亲友微信主体 AES-GCM 密文
 * @param wechatLocatorCipher 本机稳定定位密文，未验证时为空
 * @param wechatLocatorHash 本机稳定定位 HMAC，未验证时为空
 * @param remarkCipher 当前微信备注密文，可空
 * @param wechatVersion 最近验证使用的微信版本，可空
 * @param localVerificationVersion 本机验证规则版本，可空
 * @param verificationIdempotencyKeyHash 最近验证幂等键 SHA-256，可空
 * @param verificationRequestHash 最近验证请求指纹 SHA-256，可空
 * @param unbindIdempotencyKeyHash 最近解绑幂等键 SHA-256，可空
 * @param unbindRequestHash 最近解绑请求指纹 SHA-256，可空
 * @param verifiedAt 最近验证时间，可空
 * @param relationship 可选关系说明
 * @param consentPolicyVersion 亲友同意的政策版本，可空
 * @param consentedAt 亲友明确同意时间，可空
 * @param status 绑定状态
 * @param createdBy 创建该绑定的账号 UUID
 * @param version 乐观锁版本，数据库从 0 开始
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param revokedAt 解除时间，可空
 * @author Codex
 * @since 1.0.0
 */
public record ContactBinding(
        UUID id,
        UUID ownerUserId,
        byte[] contactSubjectHash,
        byte[] contactSubjectCipher,
        byte[] wechatLocatorCipher,
        byte[] wechatLocatorHash,
        byte[] remarkCipher,
        String wechatVersion,
        String localVerificationVersion,
        byte[] verificationIdempotencyKeyHash,
        byte[] verificationRequestHash,
        byte[] unbindIdempotencyKeyHash,
        byte[] unbindRequestHash,
        Instant verifiedAt,
        String relationship,
        String consentPolicyVersion,
        Instant consentedAt,
        ContactStatus status,
        UUID createdBy,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant revokedAt) {

    /**
     * 防止外部修改敏感字节数组。
     */
    public ContactBinding {
        contactSubjectHash = contactSubjectHash.clone();
        contactSubjectCipher = cloneNullable(contactSubjectCipher);
        wechatLocatorCipher = cloneNullable(wechatLocatorCipher);
        wechatLocatorHash = cloneNullable(wechatLocatorHash);
        remarkCipher = cloneNullable(remarkCipher);
        verificationIdempotencyKeyHash = cloneNullable(verificationIdempotencyKeyHash);
        verificationRequestHash = cloneNullable(verificationRequestHash);
        unbindIdempotencyKeyHash = cloneNullable(unbindIdempotencyKeyHash);
        unbindRequestHash = cloneNullable(unbindRequestHash);
    }

    /**
     * 创建尚未携带亲友同意证据的兼容绑定快照。
     *
     * @param id 绑定 UUID
     * @param ownerUserId owner UUID
     * @param contactSubjectHash 微信主体 HMAC
     * @param contactSubjectCipher 微信主体密文
     * @param wechatLocatorCipher 稳定定位密文，可空
     * @param wechatLocatorHash 稳定定位 HMAC，可空
     * @param remarkCipher 备注密文，可空
     * @param localVerificationVersion 本机验证规则版本，可空
     * @param verifiedAt 验证时间，可空
     * @param relationship 关系说明，可空
     * @param status 绑定状态
     * @param createdBy 创建账号 UUID
     * @param version 乐观锁版本
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param revokedAt 解除时间，可空
     */
    public ContactBinding(
            UUID id,
            UUID ownerUserId,
            byte[] contactSubjectHash,
            byte[] contactSubjectCipher,
            byte[] wechatLocatorCipher,
            byte[] wechatLocatorHash,
            byte[] remarkCipher,
            String localVerificationVersion,
            Instant verifiedAt,
            String relationship,
            ContactStatus status,
            UUID createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt) {
        this(id, ownerUserId, contactSubjectHash, contactSubjectCipher,
                wechatLocatorCipher, wechatLocatorHash, remarkCipher,
                null, localVerificationVersion, null, null, null, null,
                verifiedAt, relationship, null, null,
                status, createdBy, version, createdAt, updatedAt, revokedAt);
    }

    /**
     * 返回亲友微信主体 HMAC 的防御性副本。
     *
     * @return 亲友微信主体 HMAC 副本
     */
    @Override
    public byte[] contactSubjectHash() {
        return contactSubjectHash.clone();
    }

    /**
     * 返回亲友微信主体密文的防御性副本。
     *
     * @return 亲友微信主体密文副本
     */
    @Override
    public byte[] contactSubjectCipher() {
        return cloneNullable(contactSubjectCipher);
    }

    /**
     * 返回稳定定位密文的防御性副本。
     *
     * @return 稳定定位密文副本，未验证时为空
     */
    @Override
    public byte[] wechatLocatorCipher() {
        return cloneNullable(wechatLocatorCipher);
    }

    /**
     * 返回稳定定位 HMAC 的防御性副本。
     *
     * @return 稳定定位 HMAC 副本，未验证时为空
     */
    @Override
    public byte[] wechatLocatorHash() {
        return cloneNullable(wechatLocatorHash);
    }

    /**
     * 返回微信备注密文的防御性副本。
     *
     * @return 微信备注密文副本，不存在时为空
     */
    @Override
    public byte[] remarkCipher() {
        return cloneNullable(remarkCipher);
    }

    /**
     * 返回最近验证幂等键摘要的防御性副本。
     *
     * @return 幂等键 SHA-256 副本，可空
     */
    @Override
    public byte[] verificationIdempotencyKeyHash() {
        return cloneNullable(verificationIdempotencyKeyHash);
    }

    /**
     * 返回最近验证请求指纹的防御性副本。
     *
     * @return 请求指纹 SHA-256 副本，可空
     */
    @Override
    public byte[] verificationRequestHash() {
        return cloneNullable(verificationRequestHash);
    }

    /**
     * 返回最近解绑幂等键摘要的防御性副本。
     *
     * @return 解绑幂等键 SHA-256 副本，可空
     */
    @Override
    public byte[] unbindIdempotencyKeyHash() {
        return cloneNullable(unbindIdempotencyKeyHash);
    }

    /**
     * 返回最近解绑请求指纹的防御性副本。
     *
     * @return 解绑请求指纹 SHA-256 副本，可空
     */
    @Override
    public byte[] unbindRequestHash() {
        return cloneNullable(unbindRequestHash);
    }

    /**
     * 根据有效称呼是否存在更新联系人状态和聚合版本。
     *
     * <p>调用方必须已验证当前绑定处于 ACTIVE_NO_ALIAS 或 ACTIVE，并持有 owner 与联系人写锁。
     *
     * @param hasActiveAlias 更新后是否仍有至少一个有效称呼
     * @param now 更新时间
     * @return 更新后的联系人绑定快照
     */
    public ContactBinding withAliasPresence(boolean hasActiveAlias, Instant now) {
        ContactStatus nextStatus = hasActiveAlias
                ? ContactStatus.ACTIVE : ContactStatus.ACTIVE_NO_ALIAS;
        return new ContactBinding(
                id, ownerUserId, contactSubjectHash, contactSubjectCipher,
                wechatLocatorCipher, wechatLocatorHash, remarkCipher,
                wechatVersion, localVerificationVersion,
                verificationIdempotencyKeyHash, verificationRequestHash,
                unbindIdempotencyKeyHash, unbindRequestHash,
                verifiedAt, relationship, consentPolicyVersion, consentedAt,
                nextStatus, createdBy, version, createdAt, now, revokedAt);
    }

    private static byte[] cloneNullable(byte[] value) {
        return value == null ? null : value.clone();
    }
}
