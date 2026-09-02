package com.aifriend.identity.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.identity.domain.RefreshTokenStatus;
import com.aifriend.identity.domain.StoredRefreshToken;
import com.aifriend.identity.domain.UserAccount;
import com.aifriend.identity.domain.UserStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.IdentitySecurityProperties;

/**
 * token family 创建和刷新令牌原子轮换事务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TokenTransactionService {

    private final RefreshTokenRepositoryPort refreshTokenRepositoryPort;
    private final UserAccountPort userAccountPort;
    private final RefreshTokenCodec refreshTokenCodec;
    private final AccessTokenPort accessTokenPort;
    private final AuditEventPort auditEventPort;
    private final IdentitySecurityProperties properties;
    private final Clock clock;

    /**
     * 创建令牌事务服务。
     *
     * @param refreshTokenRepositoryPort 刷新令牌持久化端口
     * @param userAccountPort 用户账号端口
     * @param refreshTokenCodec 刷新令牌编码器
     * @param accessTokenPort 访问令牌签发端口
     * @param auditEventPort 安全审计端口
     * @param properties 身份安全配置
     * @param clock UTC 时钟
     */
    public TokenTransactionService(
            RefreshTokenRepositoryPort refreshTokenRepositoryPort,
            UserAccountPort userAccountPort,
            RefreshTokenCodec refreshTokenCodec,
            AccessTokenPort accessTokenPort,
            AuditEventPort auditEventPort,
            IdentitySecurityProperties properties,
            Clock clock) {
        this.refreshTokenRepositoryPort = refreshTokenRepositoryPort;
        this.userAccountPort = userAccountPort;
        this.refreshTokenCodec = refreshTokenCodec;
        this.accessTokenPort = accessTokenPort;
        this.auditEventPort = auditEventPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 创建新 token family 并签发令牌对。
     *
     * @param user ACTIVE 用户
     * @param devicePublicKeySha256 已验证设备公钥摘要；门禁关闭时为空
     * @return 令牌对
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenPairResult issue(UserAccount user, byte[] devicePublicKeySha256) {
        Instant now = Instant.now(clock);
        UUID familyId = UUID.randomUUID();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        GeneratedRefreshToken refreshToken = refreshTokenCodec.generate(familyId, user.id(), refreshExpiresAt);
        refreshTokenRepositoryPort.createFamily(familyId, user.id(), devicePublicKeySha256, now);
        refreshTokenRepositoryPort.saveToken(refreshToken.storedToken(), now);
        AccessToken accessToken = accessTokenPort.issue(user, now, devicePublicKeySha256);
        auditEventPort.append(user.id(), "AUTH_LOGIN", "SUCCESS", null, now);
        return new TokenPairResult(
                accessToken.value(),
                accessToken.expiresAt(),
                refreshToken.value(),
                refreshExpiresAt,
                user);
    }

    /**
     * 原子消费旧刷新令牌并签发同 family 新令牌。
     *
     * <p>检测到已轮换令牌重用时先撤销整个 family 并提交审计，再返回 compromised 结果；
     * 调用方必须在事务提交后向客户端返回 AUTH_REQUIRED。
     *
     * @param refreshTokenValue 刷新令牌明文，不得记录日志
     * @param devicePublicKeySha256 已验证设备公钥摘要；门禁关闭时为空
     * @return 轮换事务结果
     * @throws BusinessException 当刷新令牌不存在或账号不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenRotationResult rotate(String refreshTokenValue, byte[] devicePublicKeySha256) {
        Instant now = Instant.now(clock);
        StoredRefreshToken storedToken = refreshTokenRepositoryPort
                .findByTokenHashForUpdate(refreshTokenCodec.digest(refreshTokenValue))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        if (!MessageDigest.isEqual(
                nullableDigest(storedToken.devicePublicKeySha256()),
                nullableDigest(devicePublicKeySha256))) {
            refreshTokenRepositoryPort.revokeFamily(storedToken.familyId(), now);
            auditEventPort.append(
                    storedToken.userId(),
                    "AUTH_REFRESH_DEVICE",
                    "REVOKED",
                    "DEVICE_MISMATCH",
                    now);
            return TokenRotationResult.compromised();
        }
        if (storedToken.status() != RefreshTokenStatus.ACTIVE) {
            refreshTokenRepositoryPort.revokeFamily(storedToken.familyId(), now);
            auditEventPort.append(storedToken.userId(), "AUTH_REFRESH_REUSE", "REVOKED", "TOKEN_REUSE", now);
            return TokenRotationResult.compromised();
        }
        if (!storedToken.expiresAt().isAfter(now)) {
            refreshTokenRepositoryPort.revokeFamily(storedToken.familyId(), now);
            return TokenRotationResult.expired();
        }
        UserAccount user = userAccountPort.findById(storedToken.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        if (user.status() != UserStatus.ACTIVE) {
            refreshTokenRepositoryPort.revokeFamily(storedToken.familyId(), now);
            return TokenRotationResult.expired();
        }
        refreshTokenRepositoryPort.markRotated(storedToken.id(), now);
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        GeneratedRefreshToken newRefreshToken = refreshTokenCodec.generate(
                storedToken.familyId(), user.id(), refreshExpiresAt);
        refreshTokenRepositoryPort.saveToken(newRefreshToken.storedToken(), now);
        AccessToken accessToken = accessTokenPort.issue(user, now, devicePublicKeySha256);
        auditEventPort.append(user.id(), "AUTH_REFRESH", "SUCCESS", null, now);
        TokenPairResult tokenPair = new TokenPairResult(
                accessToken.value(),
                accessToken.expiresAt(),
                newRefreshToken.value(),
                refreshExpiresAt,
                user);
        return TokenRotationResult.success(tokenPair);
    }

    private byte[] nullableDigest(byte[] digest) {
        return digest == null ? new byte[0] : digest;
    }
}
