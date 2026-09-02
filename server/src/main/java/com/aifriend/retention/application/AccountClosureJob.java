package com.aifriend.retention.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 内部账号注销清理作业。
 *
 * @param id 注销作业 UUID
 * @param ownerUserId owner UUID
 * @param acceptedAt 可靠受理时间
 * @param retryCount 已失败重试次数
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureJob(
        UUID id,
        UUID ownerUserId,
        Instant acceptedAt,
        int retryCount) {
}
