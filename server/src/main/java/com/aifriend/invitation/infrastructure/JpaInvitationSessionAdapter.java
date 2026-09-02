package com.aifriend.invitation.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.invitation.application.InvitationSessionRepositoryPort;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;

/**
 * MySQL 受限邀请会话持久化适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaInvitationSessionAdapter implements InvitationSessionRepositoryPort {

    private static final List<InvitationSessionStatus> ACTIVE_STATUSES = List.of(
            InvitationSessionStatus.AWAITING_WECHAT_OAUTH,
            InvitationSessionStatus.WECHAT_VERIFIED);

    private final InvitationSessionJpaRepository repository;

    /**
     * 创建邀请会话持久化适配器。
     *
     * @param repository 会话 Repository
     */
    public JpaInvitationSessionAdapter(InvitationSessionJpaRepository repository) {
        this.repository = repository;
    }

    /**
     * 按 Cookie 令牌摘要读取候选会话。
     *
     * @param sessionTokenDigest Cookie 令牌摘要
     * @return 候选会话，不存在时为空
     */
    @Override
    public Optional<InvitationSession> findBySessionTokenDigest(byte[] sessionTokenDigest) {
        return repository.findBySessionTokenDigest(sessionTokenDigest).map(this::toDomain);
    }

    /**
     * 按 UUID 加写锁查询会话。
     *
     * @param sessionId 会话 UUID
     * @return 加锁后的会话，不存在时为空
     */
    @Override
    public Optional<InvitationSession> findByIdForUpdate(UUID sessionId) {
        return repository.findByIdForUpdate(sessionId).map(this::toDomain);
    }

    /**
     * 保存不含任何会话凭据明文的邀请会话。
     *
     * @param session 会话快照
     * @return 保存后的会话
     */
    @Override
    public InvitationSession save(InvitationSession session) {
        InvitationSessionEntity saved = repository.save(new InvitationSessionEntity(
                session.id(), session.invitationId(), session.sessionTokenDigest(),
                session.csrfTokenDigest(), session.oauthStateDigest(),
                session.declineIdempotencyKeyDigest(), session.acceptIdempotencyKeyDigest(),
                session.oauthSubjectHash(), session.oauthSubjectCipher(),
                session.oauthVerifiedAt(), session.acceptedConsentPolicyVersion(), session.status(),
                session.expiresAt(), session.createdAt(), session.terminatedAt(), session.version()));
        return toDomain(saved);
    }

    /**
     * 因邀请撤销而终止对应活动会话。
     *
     * @param invitationId 邀请 UUID
     * @param now 当前 UTC 时间
     * @return 更新行数
     */
    @Override
    public int terminateByInvitationId(UUID invitationId, Instant now) {
        return repository.terminateByInvitationId(
                invitationId, ACTIVE_STATUSES, InvitationSessionStatus.TERMINATED, now);
    }

    private InvitationSession toDomain(InvitationSessionEntity entity) {
        return new InvitationSession(
                entity.getId(), entity.getInvitationId(), entity.getSessionTokenDigest(),
                entity.getCsrfTokenDigest(), entity.getOauthStateDigest(),
                entity.getDeclineIdempotencyKeyDigest(), entity.getAcceptIdempotencyKeyDigest(),
                entity.getOauthSubjectHash(), entity.getOauthSubjectCipher(),
                entity.getOauthVerifiedAt(), entity.getAcceptedConsentPolicyVersion(), entity.getStatus(),
                entity.getExpiresAt(), entity.getCreatedAt(), entity.getTerminatedAt(), entity.getVersion());
    }
}
