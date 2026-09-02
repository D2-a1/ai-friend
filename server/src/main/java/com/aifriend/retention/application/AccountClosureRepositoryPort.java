package com.aifriend.retention.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 账号注销可靠受理事务端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountClosureRepositoryPort {

    /**
     * 原子记录注销、停用账号、撤销业务能力并写入 Outbox。
     *
     * @param ownerUserId 当前认证 owner UUID
     * @param idempotencyKeyHash 幂等键 SHA-256
     * @param requestHash 请求语义 SHA-256
     * @param expectedVersion 可选的账号版本前置条件
     * @param acceptedAt UTC 受理时间
     * @param reRegistrationNotBefore 最早重新注册时间
     * @return 首次受理或同键同正文安全重放结果
     */
    AccountClosureView accept(
            UUID ownerUserId,
            byte[] idempotencyKeyHash,
            byte[] requestHash,
            Long expectedVersion,
            Instant acceptedAt,
            Instant reRegistrationNotBefore);
}
