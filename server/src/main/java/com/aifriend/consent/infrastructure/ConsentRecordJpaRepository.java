package com.aifriend.consent.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aifriend.consent.domain.ConsentType;

/**
 * 授权记录 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ConsentRecordJpaRepository extends JpaRepository<ConsentRecordEntity, UUID> {

    /**
     * 按用户查询全部历史，最新记录在前。
     *
     * @param userId 用户 UUID
     * @return 历史记录
     */
    List<ConsentRecordEntity> findByUserIdOrderBySequenceNoDesc(UUID userId);

    /**
     * 查询用户指定授权类型的最新追加记录。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @return 最新授权实体
     */
    Optional<ConsentRecordEntity> findFirstByUserIdAndTypeOrderBySequenceNoDesc(
            UUID userId,
            ConsentType type);

    /**
     * 按用户、授权类型和幂等键摘要查询原记录。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @param idempotencyKeyHash 幂等键摘要
     * @return 原记录
     */
    Optional<ConsentRecordEntity> findByUserIdAndTypeAndIdempotencyKeyHash(
            UUID userId,
            ConsentType type,
            byte[] idempotencyKeyHash);
}
