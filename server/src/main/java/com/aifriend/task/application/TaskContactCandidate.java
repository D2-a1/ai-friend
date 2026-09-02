package com.aifriend.task.application;

import java.util.UUID;

/**
 * 事务外声学联系人候选最小快照。
 *
 * @param candidateId 当前会话候选随机编号
 * @param contactId 联系人内部 UUID
 * @param publicContactId ct_ 前缀联系人编号
 * @param displayName 最小展示名称
 * @param alias 命中的方言称呼
 * @param scoreBand UNIQUE 或 AMBIGUOUS
 * @param rank 候选顺序，1—3
 * @author Codex
 * @since 1.0.0
 */
public record TaskContactCandidate(
        String candidateId,
        UUID contactId,
        String publicContactId,
        String displayName,
        String alias,
        String scoreBand,
        int rank) {
}
