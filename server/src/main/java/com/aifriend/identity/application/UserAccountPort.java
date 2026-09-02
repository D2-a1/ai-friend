package com.aifriend.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.identity.domain.ProtectedWechatSubject;
import com.aifriend.identity.domain.UserAccount;

/**
 * 用户账号持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface UserAccountPort {

    /**
     * 按微信主体 HMAC 查询账号。
     *
     * @param subjectHash 微信主体查询键
     * @return 账号快照
     */
    Optional<UserAccount> findByWechatSubjectHash(byte[] subjectHash);

    /**
     * 按内部 UUID 查询账号。
     *
     * @param userId 用户 UUID
     * @return 账号快照
     */
    Optional<UserAccount> findById(UUID userId);

    /**
     * 创建指定代次账号。
     *
     * @param protectedSubject 加密主体和查询键
     * @param accountGeneration 账号代次
     * @param createdAt 创建时间
     * @return 新账号或并发创建的同主体账号
     */
    UserAccount create(
            ProtectedWechatSubject protectedSubject,
            long accountGeneration,
            Instant createdAt);
}
