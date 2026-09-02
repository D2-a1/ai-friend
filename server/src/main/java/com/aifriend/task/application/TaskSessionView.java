package com.aifriend.task.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.aifriend.task.domain.TaskAction;
import com.aifriend.task.domain.TaskState;

/**
 * 不暴露内部 UUID、密文或微信主体的任务会话响应。
 *
 * @param sessionId ts_ 前缀会话编号
 * @param sessionVersion 从 1 开始的状态版本
 * @param state 当前任务状态
 * @param understanding 临时理解结果，可空
 * @param candidates 最多三个候选
 * @param spokenSummary 完整复述文字，可空
 * @param summaryHash 复述 SHA-256 Base64URL，可空
 * @param allowedActions 当前允许动作
 * @param channelResult 渠道结果，可空
 * @param expiresAt 会话过期时间
 * @author Codex
 * @since 1.0.0
 */
public record TaskSessionView(
        String sessionId,
        long sessionVersion,
        TaskState state,
        TaskUnderstandingView understanding,
        List<TaskCandidateView> candidates,
        String spokenSummary,
        String summaryHash,
        Set<TaskAction> allowedActions,
        TaskChannelResultView channelResult,
        Instant expiresAt) {

    /** 固化候选与动作集合。 */
    public TaskSessionView {
        candidates = List.copyOf(candidates);
        allowedActions = Set.copyOf(allowedActions);
    }
}
