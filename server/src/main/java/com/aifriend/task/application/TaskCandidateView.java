package com.aifriend.task.application;

/**
 * 任务响应中的候选联系人。
 *
 * @param candidateId 当前会话候选编号
 * @param contact 联系人最小展示快照
 * @param scoreBand UNIQUE 或 AMBIGUOUS
 * @param rank 候选顺序
 * @author Codex
 * @since 1.0.0
 */
public record TaskCandidateView(
        String candidateId,
        TaskMatchedContactView contact,
        String scoreBand,
        int rank) {
}
