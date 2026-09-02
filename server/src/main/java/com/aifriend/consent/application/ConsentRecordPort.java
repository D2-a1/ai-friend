package com.aifriend.consent.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.consent.domain.ConsentRecord;
import com.aifriend.consent.domain.ConsentType;

/**
 * 追加式授权记录持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ConsentRecordPort {

    /**
     * 查询用户全部历史授权记录，按服务端决定时间倒序。
     *
     * @param userId 用户 UUID
     * @return 历史记录
     */
    List<ConsentRecord> listByUser(UUID userId);

    /**
     * 查询用户指定授权类型的最新决定。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @return 最新授权记录，尚未决定时为空
     */
    Optional<ConsentRecord> findLatest(UUID userId, ConsentType type);

    /**
     * 按用户、类型和幂等键摘要查询原记录。
     *
     * @param userId 用户 UUID
     * @param type 授权类型
     * @param idempotencyKeyHash 幂等键摘要
     * @return 原授权记录
     */
    Optional<ConsentRecord> findByIdempotencyKey(
            UUID userId,
            ConsentType type,
            byte[] idempotencyKeyHash);

    /**
     * 以数据库唯一键追加授权记录。
     *
     * @param record 新记录
     * @return true 表示本次插入，false 表示幂等键已存在
     */
    boolean append(ConsentRecord record);
}
