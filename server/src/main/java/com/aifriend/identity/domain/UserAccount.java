package com.aifriend.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 身份域用户账号快照。
 *
 * @param id 内部 UUID
 * @param status 账号状态
 * @param accountGeneration 账号代次
 * @param createdAt 创建时间
 * @author Codex
 * @since 1.0.0
 */
public record UserAccount(
        UUID id,
        UserStatus status,
        long accountGeneration,
        Instant createdAt) {
}
