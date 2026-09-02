package com.aifriend.task.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 任务候选关系持久化快照，不含展示文字。
 *
 * @param id 候选 UUID
 * @param taskSessionId 会话 UUID
 * @param ownerUserId owner UUID
 * @param contactId 联系人 UUID
 * @param scoreBand UNIQUE 或 AMBIGUOUS
 * @param rank 顺序 1—3
 * @param createdAt 创建时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskStoredCandidate(
        UUID id,
        UUID taskSessionId,
        UUID ownerUserId,
        UUID contactId,
        String scoreBand,
        int rank,
        Instant createdAt) {
}
