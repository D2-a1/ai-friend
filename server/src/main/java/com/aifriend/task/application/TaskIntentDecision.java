package com.aifriend.task.application;

import com.aifriend.task.domain.TaskIntent;

/**
 * 本地有限意图解析决定。
 *
 * <p>决定只用于后续候选生成。出现多个动作、低信息回答或帮助请求时，
 * {@code requiresRetry} 为真，调用方必须要求重说，不能选择其中一个动作。
 *
 * @param intent 有限通信意图
 * @param requiresRetry 是否必须要求重说
 * @author Codex
 * @since 1.0.0
 */
public record TaskIntentDecision(
        TaskIntent intent,
        boolean requiresRetry) {
}
