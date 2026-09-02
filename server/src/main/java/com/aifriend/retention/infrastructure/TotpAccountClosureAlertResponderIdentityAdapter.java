package com.aifriend.retention.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.retention.application.AccountClosureAlertResponderIdentity;
import com.aifriend.retention.application.AccountClosureAlertResponderIdentityPort;
import com.aifriend.retention.application.OperationsTotpProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 个人运维 TOTP 注销告警接手身份适配器。
 *
 * <p>只允许服务器固定的值班责任组，验证码采用标准六位 TOTP，并通过 Redis
 * 完成尝试限制和时间步单次消费。凭据、TOTP 秘密和匿名主体标识均不得记录日志。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public final class TotpAccountClosureAlertResponderIdentityAdapter
        implements AccountClosureAlertResponderIdentityPort {

    /** 验证事实的最短充分有效期。 */
    private static final long IDENTITY_VALIDITY_SECONDS = 60L;

    /** 个人运维身份配置。 */
    private final OperationsTotpProperties properties;
    /** 标准 TOTP 验证器。 */
    private final StandardTotpVerifier totpVerifier;
    /** Redis 尝试限制与防重放门禁。 */
    private final RedisOperationsTotpGuard totpGuard;
    /** 主体域隔离 HMAC 组件。 */
    private final SensitiveDataProtector sensitiveDataProtector;
    /** SHA-256 摘要组件。 */
    private final DigestService digestService;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建个人运维 TOTP 身份适配器。
     *
     * @param properties 个人运维身份配置
     * @param totpGuard Redis 尝试限制与防重放门禁
     * @param sensitiveDataProtector 主体域隔离 HMAC 组件
     * @param digestService SHA-256 摘要组件
     * @param clock UTC 时钟
     */
    TotpAccountClosureAlertResponderIdentityAdapter(
            OperationsTotpProperties properties,
            RedisOperationsTotpGuard totpGuard,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            Clock clock) {
        this.properties = properties;
        this.totpVerifier = new StandardTotpVerifier(properties.secretBase32());
        this.totpGuard = totpGuard;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.digestService = digestService;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public AccountClosureAlertResponderIdentity verify(
            UUID deliveryId,
            byte[] credentialProof) {
        if (deliveryId == null) {
            throw new IllegalArgumentException("告警投递 UUID 不能为空");
        }
        totpGuard.acquireAttempt(deliveryId);
        try {
            Instant verifiedAt = Instant.now(clock);
            long matchingCounter = totpVerifier.findMatchingCounter(
                    credentialProof,
                    verifiedAt);
            if (matchingCounter < 0L) {
                throw new BusinessException(ErrorCode.OPERATIONS_CREDENTIAL_INVALID);
            }
            totpGuard.consumeCounter(properties.subjectId(), matchingCounter);
            byte[] subjectHash = sensitiveDataProtector.subjectHmac(
                    "operations-totp-subject-v1:" + properties.subjectId());
            byte[] contextHash = digestService.sha256(
                    "operations-totp-auth-v1\n"
                            + properties.subjectId() + "\n"
                            + deliveryId + "\n"
                            + matchingCounter);
            return new AccountClosureAlertResponderIdentity(
                    AccountClosureAlertAudience.ON_CALL,
                    subjectHash,
                    contextHash,
                    verifiedAt,
                    verifiedAt.plusSeconds(IDENTITY_VALIDITY_SECONDS));
        } finally {
            if (credentialProof != null) {
                Arrays.fill(credentialProof, (byte) 0);
            }
        }
    }
}
