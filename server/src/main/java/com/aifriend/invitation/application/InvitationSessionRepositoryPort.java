package com.aifriend.invitation.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.invitation.domain.InvitationSession;

/**
 * 受限邀请会话持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface InvitationSessionRepositoryPort {

    /**
     * 按 Cookie 令牌摘要读取候选会话，不加锁且不返回任何凭据明文。
     *
     * @param sessionTokenDigest Cookie 令牌 SHA-256 摘要
     * @return 候选会话，不存在时为空
     */
    Optional<InvitationSession> findBySessionTokenDigest(byte[] sessionTokenDigest);

    /**
     * 按会话 UUID 加写锁查询，用于终态决策的并发复验。
     *
     * @param sessionId 会话 UUID
     * @return 加锁后的会话，不存在时为空
     */
    Optional<InvitationSession> findByIdForUpdate(UUID sessionId);

    /**
     * 保存只含凭据摘要的邀请会话。
     *
     * @param session 会话快照
     * @return 保存后的会话
     */
    InvitationSession save(InvitationSession session);

    /**
     * 因邀请撤销而终止尚未结束的会话。
     *
     * @param invitationId 邀请 UUID
     * @param now 当前 UTC 时间
     * @return 更新行数
     */
    int terminateByInvitationId(UUID invitationId, Instant now);
}
