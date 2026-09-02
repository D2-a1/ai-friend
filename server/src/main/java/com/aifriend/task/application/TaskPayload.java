package com.aifriend.task.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.aifriend.task.domain.TaskAction;

/**
 * 必须整体加密后持久化的任务敏感载荷。
 *
 * @param context 创建时固化的版本上下文
 * @param understanding 临时语音理解结果
 * @param candidates 候选展示快照
 * @param spokenSummary 完整复述，可空
 * @param allowedActions 当前允许动作
 * @param confirmationStartedAt 当前确认窗口起点，可空
 * @param actionPlan 有限微信动作计划，可空
 * @param channelResult 渠道结果，可空
 * @param routineCommandLearningEvidence 可空的日常动作声学范围证据
 * @author Codex
 * @since 1.0.0
 */
public record TaskPayload(
        TaskClientContext context,
        TaskUnderstandingView understanding,
        List<TaskCandidateView> candidates,
        String spokenSummary,
        Set<TaskAction> allowedActions,
        Instant confirmationStartedAt,
        WechatActionPlanView actionPlan,
        TaskChannelResultView channelResult,
        RoutineCommandLearningEvidence routineCommandLearningEvidence) {

    /**
     * 创建不含日常指令学习证据的兼容载荷。
     *
     * @param context 创建时固化的版本上下文
     * @param understanding 临时语音理解结果
     * @param candidates 候选展示快照
     * @param spokenSummary 完整复述，可空
     * @param allowedActions 当前允许动作
     * @param confirmationStartedAt 当前确认窗口起点，可空
     * @param actionPlan 有限微信动作计划，可空
     * @param channelResult 渠道结果，可空
     */
    public TaskPayload(
            TaskClientContext context,
            TaskUnderstandingView understanding,
            List<TaskCandidateView> candidates,
            String spokenSummary,
            Set<TaskAction> allowedActions,
            Instant confirmationStartedAt,
            WechatActionPlanView actionPlan,
            TaskChannelResultView channelResult) {
        this(context, understanding, candidates, spokenSummary, allowedActions,
                confirmationStartedAt, actionPlan, channelResult, null);
    }

    /** 固化载荷集合。 */
    public TaskPayload {
        candidates = List.copyOf(candidates);
        allowedActions = Set.copyOf(allowedActions);
    }
}
