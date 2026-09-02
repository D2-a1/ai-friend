package com.aifriend.invitation.application;

import java.time.Clock;
import java.time.Instant;
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
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 邀请 OAuth 身份的原子提交服务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class InvitationOAuthCompletionService {

    private final InvitationRepositoryPort invitationRepositoryPort;
    private final InvitationSessionRepositoryPort sessionRepositoryPort;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final DigestService digestService;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建 OAuth 结果提交服务。
     *
     * @param invitationRepositoryPort 邀请持久化端口
     * @param sessionRepositoryPort 会话持久化端口
     * @param sensitiveDataProtector 敏感主体加密与 HMAC 服务
     * @param digestService 摘要比较服务
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public InvitationOAuthCompletionService(
            InvitationRepositoryPort invitationRepositoryPort,
            InvitationSessionRepositoryPort sessionRepositoryPort,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.invitationRepositoryPort = invitationRepositoryPort;
        this.sessionRepositoryPort = sessionRepositoryPort;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.digestService = digestService;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 重新加锁复验并保存加密微信身份，同时消费一次性 state。
     *
     * @param invitationId 邀请 UUID
     * @param sessionId 会话 UUID
     * @param sessionDigest Cookie 摘要
     * @param stateDigest OAuth state 摘要
     * @param identity 已验证最小微信身份
     * @throws BusinessException 当状态在外部调用期间变化时抛出统一错误
     */
    @Transactional(rollbackFor = Exception.class)
    public void commit(
            UUID invitationId,
            UUID sessionId,
            byte[] sessionDigest,
            byte[] stateDigest,
            WechatInvitationIdentity identity) {
        ContactInvitation invitation = invitationRepositoryPort
                .findByIdForUpdate(invitationId)
                .orElseThrow(this::unavailable);
        InvitationSession session = sessionRepositoryPort.findByIdForUpdate(sessionId)
                .orElseThrow(this::unavailable);
        Instant now = Instant.now(clock);
        if (!session.invitationId().equals(invitation.id())
                || !digestService.constantTimeEquals(session.sessionTokenDigest(), sessionDigest)
                || !digestService.constantTimeEquals(session.oauthStateDigest(), stateDigest)
                || session.status() != InvitationSessionStatus.AWAITING_WECHAT_OAUTH
                || invitation.status() != InvitationStatus.PROOF_REDEEMED
                || !session.expiresAt().isAfter(now)
                || !invitation.expiresAt().isAfter(now)) {
            throw unavailable();
        }
        byte[] subjectHash = sensitiveDataProtector.subjectHmac(identity.subject());
        byte[] subjectCipher = sensitiveDataProtector.encrypt(identity.subject());
        InvitationSession verifiedSession = new InvitationSession(
                session.id(), session.invitationId(), session.sessionTokenDigest(),
                session.csrfTokenDigest(),
                digestService.sha256("oauth-state-consumed:" + session.id()),
                session.declineIdempotencyKeyDigest(), session.acceptIdempotencyKeyDigest(),
                subjectHash, subjectCipher, now, session.acceptedConsentPolicyVersion(),
                InvitationSessionStatus.WECHAT_VERIFIED, session.expiresAt(),
                session.createdAt(), null, session.version());
        ContactInvitation verifiedInvitation = new ContactInvitation(
                invitation.id(), invitation.ownerUserId(), invitation.proofDigest(),
                InvitationStatus.WECHAT_VERIFIED, invitation.expiresAt(), invitation.createdAt(),
                invitation.createIdempotencyKeyHash(), invitation.revokeIdempotencyKeyHash(),
                invitation.version());
        invitationRepositoryPort.save(verifiedInvitation);
        sessionRepositoryPort.save(verifiedSession);
        auditEventPort.append(
                invitation.ownerUserId(), "INVITATION_WECHAT_VERIFY", "SUCCESS", null, now);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.INVITATION_UNAVAILABLE);
    }
}
