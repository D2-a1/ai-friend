package com.aifriend.voicecollection.application;

import com.aifriend.consent.domain.ConsentDecision;

/**
 * 单条测试语音样本训练授权命令。
 *
 * @param decision 授予或撤回决定
 * @param confirmed 用户明确确认
 * @param policyVersion 模型训练政策版本
 * @param expectedVersion 样本乐观锁版本
 * @author codex
 * @since 1.0.0
 */
public record VoiceCollectionTrainingAuthorizationCommand(
        ConsentDecision decision,
        boolean confirmed,
        String policyVersion,
        long expectedVersion) {
}
