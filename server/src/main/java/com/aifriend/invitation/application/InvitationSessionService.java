package com.aifriend.invitation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;

/**
 * 公开 proof 单次兑换与受限邀请会话创建服务。
 *
 * <p>无论邀请不存在、proof 错误或状态不可用，都执行一次摘要比较并返回相同 410 错误。
 * 成功兑换后立即用无关墓碑覆盖原 proof 摘要，所有会话凭据只保存 SHA-256 摘要。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class InvitationSessionService {

    private static final UUID DUMMY_INVITATION_ID = new UUID(0L, 0L);
    private static final byte[] DUMMY_PROOF_DIGEST = new byte[32];

    private final InvitationRepositoryPort invitationRepositoryPort;
    private final InvitationSessionRepositoryPort sessionRepositoryPort;
    private final SecretTokenPort secretTokenPort;
    private final WechatInvitationAuthorizationPort authorizationPort;
    private final DigestService digestService;
    private final AuditEventPort auditEventPort;
    private final InvitationSessionProperties properties;
    private final Clock clock;

    /**
     * 创建公开邀请兑换服务。
     *
     * @param invitationRepositoryPort 邀请持久化端口
     * @param sessionRepositoryPort 邀请会话持久化端口
     * @param secretTokenPort 安全随机令牌端口
     * @param authorizationPort 微信 OAuth 授权入口端口
     * @param digestService 摘要与常量时间比较服务
     * @param auditEventPort 去标识化审计端口
     * @param properties 会话固定配置
     * @param clock UTC 时钟
     */
    public InvitationSessionService(
            InvitationRepositoryPort invitationRepositoryPort,
            InvitationSessionRepositoryPort sessionRepositoryPort,
            SecretTokenPort secretTokenPort,
            WechatInvitationAuthorizationPort authorizationPort,
            DigestService digestService,
            AuditEventPort auditEventPort,
            InvitationSessionProperties properties,
            Clock clock) {
        this.invitationRepositoryPort = invitationRepositoryPort;
        this.sessionRepositoryPort = sessionRepositoryPort;
        this.secretTokenPort = secretTokenPort;
        this.authorizationPort = authorizationPort;
        this.digestService = digestService;
        this.auditEventPort = auditEventPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 原子消费一次 proof 并创建固定 30 分钟的受限邀请会话。
     *
     * @param publicInvitationId 无权限公开邀请编号
     * @param proof URL fragment 中的 256 位秘密，不得记录或持久化
     * @return 只供当前响应使用的 Cookie、CSRF 和 OAuth 入口
     * @throws BusinessException 当邀请或 proof 不可继续时统一抛出 INVITATION_UNAVAILABLE
     */
    @Transactional(rollbackFor = Exception.class)
    public CreatedInvitationSession redeem(String publicInvitationId, String proof) {
        Instant now = Instant.now(clock);
        UUID invitationId = parseOrDummy(publicInvitationId);
        Optional<ContactInvitation> invitationOptional = invitationRepositoryPort.findByIdForUpdate(invitationId);
        byte[] presentedDigest = digestService.sha256(proof);
        byte[] expectedDigest = invitationOptional
                .map(ContactInvitation::proofDigest)
                .orElse(DUMMY_PROOF_DIGEST);
        boolean proofMatches = digestService.constantTimeEquals(expectedDigest, presentedDigest);
        ContactInvitation invitation = invitationOptional.orElse(null);
        if (invitation == null
                || !proofMatches
                || invitation.status() != InvitationStatus.PENDING
                || !invitation.expiresAt().isAfter(now)) {
            throw unavailable();
        }

        UUID sessionId = UUID.randomUUID();
        String sessionToken = secretTokenPort.issue();
        String csrfToken = secretTokenPort.issue();
        String oauthState = secretTokenPort.issue();
        java.net.URI authorizationUrl = authorizationPort.authorizationUrl(oauthState);
        Instant expiresAt = now.plus(properties.ttl());

        InvitationSession session = new InvitationSession(
                sessionId,
                invitation.id(),
                digestService.sha256(sessionToken),
                digestService.sha256(csrfToken),
                digestService.sha256(oauthState),
                null,
                InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
                expiresAt,
                now,
                null,
                0L);
        ContactInvitation redeemed = new ContactInvitation(
                invitation.id(),
                invitation.ownerUserId(),
                digestService.sha256("redeemed-invitation:" + sessionId),
                InvitationStatus.PROOF_REDEEMED,
                invitation.expiresAt(),
                invitation.createdAt(),
                invitation.createIdempotencyKeyHash(),
                invitation.revokeIdempotencyKeyHash(),
                invitation.version());
        invitationRepositoryPort.save(redeemed);
        sessionRepositoryPort.save(session);
        auditEventPort.append(invitation.ownerUserId(), "INVITATION_PROOF_REDEEM", "SUCCESS", null, now);
        return new CreatedInvitationSession(sessionToken, csrfToken, authorizationUrl, expiresAt);
    }

    private UUID parseOrDummy(String publicInvitationId) {
        try {
            return PublicIdCodec.parseInvitationId(publicInvitationId);
        } catch (BusinessException exception) {
            return DUMMY_INVITATION_ID;
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.INVITATION_UNAVAILABLE);
    }
}
