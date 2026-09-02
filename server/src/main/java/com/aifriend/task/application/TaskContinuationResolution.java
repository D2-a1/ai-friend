package com.aifriend.task.application;

import java.util.UUID;

/**
 * 经服务端事实核验的消息继续联系人。
 *
 * @param sourceSessionId 上一条可验证消息会话 UUID
 * @param candidate 本次新消息可沿用的唯一联系人候选
 * @author codex
 * @since 1.0.0
 */
public record TaskContinuationResolution(
        UUID sourceSessionId,
        TaskContactCandidate candidate) {
}
