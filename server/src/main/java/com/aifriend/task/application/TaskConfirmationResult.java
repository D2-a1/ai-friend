package com.aifriend.task.application;

/**
 * 动作型确认后的会话与可空有限微信计划。
 *
 * @param session 更新后的会话
 * @param actionPlan 明确确认发送或通话时生成的计划，否则为空
 * @author Codex
 * @since 1.0.0
 */
public record TaskConfirmationResult(
        TaskSessionView session,
        WechatActionPlanView actionPlan) {
}
