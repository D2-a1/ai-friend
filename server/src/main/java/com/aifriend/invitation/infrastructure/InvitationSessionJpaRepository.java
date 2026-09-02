package com.aifriend.invitation.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.aifriend.invitation.domain.InvitationSessionStatus;

/**
 * 受限邀请会话 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface InvitationSessionJpaRepository extends JpaRepository<InvitationSessionEntity, UUID> {

    /**
     * 按 Cookie 令牌摘要读取候选会话。
     *
     * @param sessionTokenDigest Cookie 令牌摘要
     * @return 候选会话
     */
    Optional<InvitationSessionEntity> findBySessionTokenDigest(byte[] sessionTokenDigest);

    /**
     * 按 UUID 加写锁读取会话。
     *
     * @param id 会话 UUID
     * @return 加锁后的会话
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from InvitationSessionEntity session where session.id = :id")
    Optional<InvitationSessionEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 终止指定邀请仍处于活动状态的受限会话。
     *
     * @param invitationId 邀请 UUID
     * @param activeStatuses 活动状态集合
     * @param terminated 终止状态
     * @param now 当前 UTC 时间
     * @return 更新行数
     */
    @Modifying
    @Query("update InvitationSessionEntity session set session.status = :terminated, "
            + "session.oauthSubjectHash = null, session.oauthSubjectCipher = null, "
            + "session.terminatedAt = :now, session.version = session.version + 1 "
            + "where session.invitationId = :invitationId and session.status in :activeStatuses")
    int terminateByInvitationId(
            @Param("invitationId") UUID invitationId,
            @Param("activeStatuses") Collection<InvitationSessionStatus> activeStatuses,
            @Param("terminated") InvitationSessionStatus terminated,
            @Param("now") Instant now);
}
