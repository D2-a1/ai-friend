package com.aifriend.invitation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

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

/**
 * 受限邀请会话的明确拒绝决策服务。
 *
 * <p>先按 Cookie 摘要定位候选会话，再固定按邀请行、会话行顺序加锁，
 * 避免与邀请人撤销形成反向锁。所有凭据只比较 SHA-256 摘要且不进入日志。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class InvitationDecisionService {

    private static final byte[] DUMMY_DIGEST = new byte[32];

    private final InvitationSessionRepositoryPort sessionRepositoryPort;
    private final InvitationRepositoryPort invitationRepositoryPort;
    private final DigestService digestService;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建邀请决策服务。
     *
     * @param sessionRepositoryPort 受限会话持久化端口
     * @param invitationRepositoryPort 邀请持久化端口
     * @param digestService 摘要与常量时间比较服务
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public InvitationDecisionService(
            InvitationSessionRepositoryPort sessionRepositoryPort,
            InvitationRepositoryPort invitationRepositoryPort,
            DigestService digestService,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.sessionRepositoryPort = sessionRepositoryPort;
        this.invitationRepositoryPort = invitationRepositoryPort;
        this.digestService = digestService;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 使用当前 Cookie、CSRF 与幂等键明确拒绝邀请。
     *
     * @param sessionToken 受限邀请 Cookie 明文，只能在当前请求内存中短暂存在
     * @param csrfToken 与当前会话绑定的 CSRF token
     * @param idempotencyKey 拒绝操作幂等键
     * @param confirmed 必须为 true 的明确拒绝标志
     * @throws BusinessException 当请求未明确确认，或会话、CSRF、邀请状态不可用时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void decline(String sessionToken, String csrfToken, String idempotencyKey, boolean confirmed) {
        if (!confirmed) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Instant now = Instant.now(clock);
        byte[] presentedSessionDigest = digestService.sha256(valueOrEmpty(sessionToken));
        byte[] presentedCsrfDigest = digestService.sha256(valueOrEmpty(csrfToken));
        byte[] idempotencyDigest = digestService.sha256(valueOrEmpty(idempotencyKey));

        Optional<InvitationSession> candidateOptional =
                sessionRepositoryPort.findBySessionTokenDigest(presentedSessionDigest);
        InvitationSession candidate = candidateOptional.orElse(null);
        byte[] expectedSessionDigest = candidate == null
                ? DUMMY_DIGEST : candidate.sessionTokenDigest();
        byte[] expectedCsrfDigest = candidate == null
                ? DUMMY_DIGEST : candidate.csrfTokenDigest();
        boolean sessionMatches = digestService.constantTimeEquals(
                expectedSessionDigest, presentedSessionDigest);
        boolean csrfMatches = digestService.constantTimeEquals(expectedCsrfDigest, presentedCsrfDigest);
        if (candidate == null || !sessionMatches || !csrfMatches) {
            throw unavailable();
        }

        ContactInvitation invitation = invitationRepositoryPort
                .findByIdForUpdate(candidate.invitationId())
                .orElseThrow(this::unavailable);
        InvitationSession session = sessionRepositoryPort.findByIdForUpdate(candidate.id())
                .orElseThrow(this::unavailable);
        if (!credentialsStillMatch(session, presentedSessionDigest, presentedCsrfDigest)) {
            throw unavailable();
        }
        if (isIdempotentReplay(session, invitation, idempotencyDigest)) {
            return;
        }
        if (!isDeclinable(session, invitation, now)) {
            throw unavailable();
        }

        ContactInvitation declined = new ContactInvitation(
                invitation.id(), invitation.ownerUserId(), invitation.proofDigest(),
                InvitationStatus.DECLINED, invitation.expiresAt(), invitation.createdAt(),
                invitation.createIdempotencyKeyHash(), invitation.revokeIdempotencyKeyHash(),
                invitation.version());
        InvitationSession terminated = new InvitationSession(
                session.id(), session.invitationId(), session.sessionTokenDigest(),
                session.csrfTokenDigest(), session.oauthStateDigest(), idempotencyDigest,
                InvitationSessionStatus.TERMINATED, session.expiresAt(), session.createdAt(),
                now, session.version());
        invitationRepositoryPort.save(declined);
        sessionRepositoryPort.save(terminated);
        auditEventPort.append(invitation.ownerUserId(), "INVITATION_DECLINE", "SUCCESS", null, now);
    }

    private boolean credentialsStillMatch(
            InvitationSession session,
            byte[] presentedSessionDigest,
            byte[] presentedCsrfDigest) {
        return digestService.constantTimeEquals(session.sessionTokenDigest(), presentedSessionDigest)
                && digestService.constantTimeEquals(session.csrfTokenDigest(), presentedCsrfDigest);
    }

    private boolean isIdempotentReplay(
            InvitationSession session,
            ContactInvitation invitation,
            byte[] idempotencyDigest) {
        return session.status() == InvitationSessionStatus.TERMINATED
                && invitation.status() == InvitationStatus.DECLINED
                && session.declineIdempotencyKeyDigest() != null
                && digestService.constantTimeEquals(
                        session.declineIdempotencyKeyDigest(), idempotencyDigest);
    }

    private boolean isDeclinable(
            InvitationSession session,
            ContactInvitation invitation,
            Instant now) {
        if (!session.expiresAt().isAfter(now) || !invitation.expiresAt().isAfter(now)) {
            return false;
        }
        return (session.status() == InvitationSessionStatus.AWAITING_WECHAT_OAUTH
                        && invitation.status() == InvitationStatus.PROOF_REDEEMED)
                || (session.status() == InvitationSessionStatus.WECHAT_VERIFIED
                        && invitation.status() == InvitationStatus.WECHAT_VERIFIED);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.INVITATION_UNAVAILABLE);
    }
}
