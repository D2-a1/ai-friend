package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.aifriend.shared.security.DigestService;

/**
 * 注销 P0 告警显式接手确认服务。
 *
 * <p>先在数据库事务外验证独立运维身份，再把不可逆身份事实交给短事务写入。
 * 普通用户 token、通知送达回执或客户端自报责任组均不能完成接手。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AccountClosureAlertAcknowledgementService {
    /** 运维凭据最大允许长度。 */
    private static final int MAX_CREDENTIAL_BYTES = 16_384;
    /** 运维身份验证事实最长有效窗口。 */
    private static final Duration MAX_IDENTITY_WINDOW = Duration.ofMinutes(5);

    /** 独立运维身份验证端口。 */
    private final AccountClosureAlertResponderIdentityPort identityPort;
    /** 接手确认短事务端口。 */
    private final AccountClosureAlertAcknowledgementRepositoryPort repositoryPort;
    /** SHA-256 摘要组件。 */
    private final DigestService digestService;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建注销 P0 告警接手确认服务。
     *
     * @param identityPort 独立运维身份验证端口
     * @param repositoryPort 接手确认短事务端口
     * @param digestService SHA-256 摘要组件
     * @param clock UTC 时钟
     */
    public AccountClosureAlertAcknowledgementService(
            AccountClosureAlertResponderIdentityPort identityPort,
            AccountClosureAlertAcknowledgementRepositoryPort repositoryPort,
            DigestService digestService,
            Clock clock) {
        this.identityPort = identityPort;
        this.repositoryPort = repositoryPort;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 使用独立运维凭据显式确认接手一个已经送达的注销 P0 告警。
     *
     * @param command P0 投递、幂等键和一次性运维凭据
     * @return 唯一接手确认及其及时或迟到分类
     * @throws RuntimeException 凭据无效、身份过期、投递未送达、越权或幂等冲突时抛出
     */
    public AccountClosureAlertAcknowledgement acknowledge(
            AccountClosureAlertAcknowledgementCommand command) {
        requireIdempotencyKey(command.idempotencyKey());
        byte[] credentialProof = command.credentialProof();
        requireCredential(credentialProof);
        try {
            Instant now = Instant.now(clock);
            AccountClosureAlertResponderIdentity identity = identityPort.verify(
                    command.deliveryId(), credentialProof);
            requireFreshIdentity(identity, now);
            byte[] requestHash = digestService.sha256(canonicalRequest(command, identity));
            return repositoryPort.acknowledge(new AccountClosureAlertAcknowledgementWrite(
                    command.deliveryId(),
                    identity.audience(),
                    identity.responderSubjectHash(),
                    identity.authenticationContextHash(),
                    digestService.sha256(command.idempotencyKey()),
                    requestHash,
                    now));
        } finally {
            Arrays.fill(credentialProof, (byte) 0);
        }
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.length() < 16
                || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("接手幂等键必须为 16 至 128 字符");
        }
    }

    private void requireCredential(byte[] credentialProof) {
        if (credentialProof == null
                || credentialProof.length == 0
                || credentialProof.length > MAX_CREDENTIAL_BYTES) {
            throw new IllegalArgumentException("独立运维身份凭据无效");
        }
    }

    private void requireFreshIdentity(
            AccountClosureAlertResponderIdentity identity,
            Instant now) {
        if (identity == null
                || identity.verifiedAt().isAfter(now)
                || !identity.expiresAt().isAfter(now)
                || identity.expiresAt().isAfter(
                        identity.verifiedAt().plus(MAX_IDENTITY_WINDOW))) {
            throw new IllegalStateException("独立运维身份验证事实无效或已过期");
        }
    }

    private String canonicalRequest(
            AccountClosureAlertAcknowledgementCommand command,
            AccountClosureAlertResponderIdentity identity) {
        return "account-closure-alert-ack-v1\n"
                + command.deliveryId() + "\n"
                + identity.audience().name() + "\n"
                + HexFormat.of().formatHex(identity.responderSubjectHash());
    }
}
