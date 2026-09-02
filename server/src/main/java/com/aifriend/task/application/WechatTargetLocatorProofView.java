package com.aifriend.task.application;

import java.time.Instant;

/**
 * 当前有限动作计划的一次性稳定定位证明。
 *
 * @param proofVersion 证明规范版本
 * @param keyId Ed25519 签名密钥编号
 * @param contactVersion 联系人聚合版本
 * @param wechatVersion 当前任务客户端上下文中的微信版本
 * @param locatorVersion 稳定定位提取规则版本
 * @param salt 当前计划 128 位随机盐的小写十六进制
 * @param targetLocatorSha256 域隔离盐化稳定定位 SHA-256
 * @param issuedAt 签发时间
 * @param expiresAt 证明过期时间
 * @param signature Ed25519 Base64URL 无填充签名
 * @author Codex
 * @since 1.0.0
 */
public record WechatTargetLocatorProofView(
        String proofVersion,
        String keyId,
        long contactVersion,
        String wechatVersion,
        String locatorVersion,
        String salt,
        String targetLocatorSha256,
        Instant issuedAt,
        Instant expiresAt,
        String signature) {

    /**
     * 返回不包含盐、摘要、签名或联系人事实的诊断文本。
     *
     * @return 脱敏诊断文本
     */
    @Override
    public String toString() {
        return "WechatTargetLocatorProofView[proofVersion=" + proofVersion
                + ", keyId=" + keyId + ", contactVersion=" + contactVersion
                + ", wechatVersion=" + wechatVersion + ", locatorVersion="
                + locatorVersion + ", issuedAt=" + issuedAt + ", expiresAt="
                + expiresAt + ", protectedFields=<redacted>]";
    }
}
