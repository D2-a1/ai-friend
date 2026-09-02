package com.aifriend.task.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * 为当前有限动作计划签发一次性、不可反查明文的稳定定位证明。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class WechatActionPlanProofIssuer {

    /** OpenAPI 固定的首版定位证明版本。 */
    public static final String PROOF_VERSION = "WECHAT_LOCATOR_PROOF_V1";
    private static final byte[] LOCATOR_DIGEST_DOMAIN =
            "ai-friend-wechat-locator-proof-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;

    private final WechatActionPlanProofSignerPort signerPort;
    private final DigestService digestService;
    private final SecureRandom secureRandom;

    /**
     * 创建动作计划定位证明签发器。
     *
     * @param signerPort Ed25519 签名端口
     * @param digestService SHA-256 服务
     */
    @Autowired
    public WechatActionPlanProofIssuer(
            WechatActionPlanProofSignerPort signerPort,
            DigestService digestService) {
        this(signerPort, digestService, new SecureRandom());
    }

    WechatActionPlanProofIssuer(
            WechatActionPlanProofSignerPort signerPort,
            DigestService digestService,
            SecureRandom secureRandom) {
        this.signerPort = signerPort;
        this.digestService = digestService;
        this.secureRandom = secureRandom;
    }

    /**
     * 使用当前联系人稳定定位签发与动作计划严格绑定的证明。
     *
     * @param claims 当前计划全部安全关键声明
     * @param stableLocator 已验证稳定定位明文，仅在本次调用内存中使用
     * @return 带 Ed25519 签名的一次性定位证明
     * @throws BusinessException 签名配置、声明或稳定定位无效时抛出
     */
    public WechatTargetLocatorProofView issue(
            WechatActionPlanProofClaims claims,
            String stableLocator) {
        validate(claims, stableLocator);
        if (!signerPort.available()) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        String locatorDigestHex = HexFormat.of().formatHex(
                digestService.sha256(locatorDigestInput(salt, stableLocator)));
        String keyId = signerPort.keyId();
        byte[] canonicalBytes = WechatActionPlanProofCanonicalizer.canonicalBytes(
                claims, PROOF_VERSION, keyId, saltHex, locatorDigestHex);
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signerPort.sign(canonicalBytes));
        return new WechatTargetLocatorProofView(
                PROOF_VERSION, keyId, claims.contactVersion(), claims.wechatVersion(),
                claims.locatorVersion(), saltHex, locatorDigestHex, claims.issuedAt(),
                claims.expiresAt(), signature);
    }

    private byte[] locatorDigestInput(byte[] salt, String stableLocator) {
        ByteArrayOutputStream value = new ByteArrayOutputStream(
                LOCATOR_DIGEST_DOMAIN.length + 1 + salt.length + stableLocator.length() * 3);
        value.writeBytes(LOCATOR_DIGEST_DOMAIN);
        value.write(0);
        value.writeBytes(salt);
        value.writeBytes(stableLocator.getBytes(StandardCharsets.UTF_8));
        return value.toByteArray();
    }

    private void validate(WechatActionPlanProofClaims claims, String stableLocator) {
        boolean invalid = claims == null
                || !StringUtils.hasText(stableLocator)
                || !StringUtils.hasText(claims.planId())
                || !StringUtils.hasText(claims.action())
                || !StringUtils.hasText(claims.contactId())
                || !StringUtils.hasText(claims.summaryHash())
                || !StringUtils.hasText(claims.minimumRuleVersion())
                || !StringUtils.hasText(claims.wechatVersion())
                || !StringUtils.hasText(claims.locatorVersion())
                || claims.contactVersion() < 0
                || claims.issuedAt() == null
                || claims.expiresAt() == null
                || !claims.expiresAt().isAfter(claims.issuedAt());
        if (invalid) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
    }
}
